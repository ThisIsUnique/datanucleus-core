/**********************************************************************
Copyright (c) 2004 Andy Jefferson and others. All rights reserved. 
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. 

Contributors:
2007 Xuan Baldauf - Contrib of notifyMainMemoryCopyIsInvalid(), findObject() (needed by DB4O plugin).
    ...
**********************************************************************/
package org.datanucleus.store;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.datanucleus.ClassConstants;
import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.PersistenceNucleusContext;
import org.datanucleus.PropertyNames;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NoExtentException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.flush.FlushNonReferential;
import org.datanucleus.flush.FlushProcess;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.identity.SCOID;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ClassMetaData;
import org.datanucleus.metadata.ClassPersistenceModifier;
import org.datanucleus.metadata.DatastoreIdentityMetaData;
import org.datanucleus.metadata.ValueGenerationStrategy;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.metadata.MetaDataManager;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.SequenceMetaData;
import org.datanucleus.metadata.TableGeneratorMetaData;
import org.datanucleus.properties.PropertyStore;
import org.datanucleus.state.StateManagerImpl;
import org.datanucleus.store.autostart.AutoStartMechanism;
import org.datanucleus.store.connection.ConnectionManager;
import org.datanucleus.store.connection.ConnectionManagerImpl;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.query.DefaultCandidateExtent;
import org.datanucleus.store.query.Extent;
import org.datanucleus.store.query.Query;
import org.datanucleus.store.query.QueryManager;
import org.datanucleus.store.query.QueryManagerImpl;
import org.datanucleus.store.schema.DefaultStoreSchemaHandler;
import org.datanucleus.store.schema.StoreSchemaHandler;
import org.datanucleus.store.schema.naming.DN2NamingFactory;
import org.datanucleus.store.schema.naming.JPANamingFactory;
import org.datanucleus.store.schema.naming.NamingCase;
import org.datanucleus.store.schema.naming.NamingFactory;
import org.datanucleus.store.types.converters.TypeConversionHelper;
import org.datanucleus.store.valuegenerator.AbstractConnectedGenerator;
import org.datanucleus.store.valuegenerator.ValueGenerationConnectionProvider;
import org.datanucleus.store.valuegenerator.ValueGenerationManager;
import org.datanucleus.store.valuegenerator.ValueGenerationManagerImpl;
import org.datanucleus.store.valuegenerator.ValueGenerator;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * An abstract representation of a Store Manager.
 * Manages the persistence of objects to the store.
 * Will be implemented for the type of datastore (RDBMS, ODBMS, etc) in question. 
 * The store manager's responsibilities include:
 * <ul>
 * <li>Creating and/or validating datastore tables according to the persistent classes being accessed by the application.</li>
 * <li>Serving as the primary intermediary between StateManagers and the database.</li>
 * <li>Serving as the base Extent and Query factory.</li>
 * </ul>
 * <p>
 * A store manager's knowledge of its contents is typically not complete. It knows about the classes that it has encountered in its lifetime. 
 * The ExecutionContext can make the StoreManager aware of a class, and can check if the StoreManager knows about a particular class.
 */
public abstract class AbstractStoreManager extends PropertyStore implements StoreManager
{
    /** Key for this StoreManager e.g "rdbms", "neo4j" */
    protected final String storeManagerKey;

    /** Nucleus Context. */
    protected PersistenceNucleusContext nucleusContext;

    /** Manager for value generation. */
    protected ValueGenerationManager valueGenerationMgr = null;

    /** Manager for the data definition in the datastore. */
    protected StoreDataManager storeDataMgr = new StoreDataManager();

    /** Persistence handler. */
    protected StorePersistenceHandler persistenceHandler = null;

    /** The flush process appropriate for this datastore. */
    protected FlushProcess flushProcess = null;

    /** Query Manager. Lazy initialised, so use getQueryManager() to access. */
    protected QueryManager queryMgr = null;

    /** Schema handler. */
    protected StoreSchemaHandler schemaHandler = null;

    /** Naming factory. */
    protected NamingFactory namingFactory = null;

    /** ConnectionManager **/
    protected ConnectionManager connectionMgr;

    /**
     * Constructor for a new StoreManager. Stores the basic information required for the datastore management.
     * @param key Key for this StoreManager
     * @param clr the ClassLoaderResolver
     * @param nucleusContext The corresponding nucleus context.
     * @param props Any properties controlling this datastore
     */
    protected AbstractStoreManager(String key, ClassLoaderResolver clr, PersistenceNucleusContext nucleusContext, Map<String, Object> props)
    {
        this.storeManagerKey = key;
        this.nucleusContext = nucleusContext;

        if (props != null)
        {
            // Store the properties for this datastore
            Iterator<Map.Entry<String, Object>> propIter = props.entrySet().iterator();
            while (propIter.hasNext())
            {
                Map.Entry<String, Object> entry = propIter.next();
                setPropertyInternal(entry.getKey(), entry.getValue());
            }
        }

        // Set up connection handling
        registerConnectionMgr();

        this.valueGenerationMgr = new ValueGenerationManagerImpl(this);

        nucleusContext.addExecutionContextListener(new ExecutionContext.LifecycleListener()
        {
            public void preClose(ExecutionContext ec)
            {
                // Close all connections for this ExecutionContext when ExecutionContext closes
                connectionMgr.closeAllConnections(ec);
            }
        });
    }

    /**
     * Register the default ConnectionManager implementation.
     * Having this in a separate method to allow overriding by store plugins if required.
     */
    protected void registerConnectionMgr()
    {
        this.connectionMgr = new ConnectionManagerImpl(this);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#close()
     */
    public synchronized void close()
    {
        connectionMgr.close();
        connectionMgr = null;

        valueGenerationMgr.clear();
        valueGenerationMgr = null;

        storeDataMgr.clear();
        storeDataMgr = null;

        if (persistenceHandler != null)
        {
            persistenceHandler.close();
            persistenceHandler = null;
        }

        if (schemaHandler != null)
        {
            schemaHandler = null;
        }

        if (queryMgr != null)
        {
            queryMgr.close();
            queryMgr = null;
        }
        nucleusContext = null;
    }

    @Override
    public boolean isClosed()
    {
        return nucleusContext == null;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getConnectionManager()
     */
    public ConnectionManager getConnectionManager()
    {
        return connectionMgr;
    }

    /**
     * Convenience accessor for the password to use for the connection.
     * Will perform decryption if the persistence property "datanucleus.ConnectionPasswordDecrypter" has also been specified.
     * @return Password
     */
    public String getConnectionPassword()
    {
        String password = getStringProperty(PropertyNames.PROPERTY_CONNECTION_PASSWORD);
        if (password != null)
        {
            String decrypterName = getStringProperty(PropertyNames.PROPERTY_CONNECTION_PASSWORD_DECRYPTER);
            if (decrypterName != null)
            {
                // Decrypt the password using the provided class
                ClassLoaderResolver clr = nucleusContext.getClassLoaderResolver(null);
                try
                {
                    Class decrypterCls = clr.classForName(decrypterName);
                    ConnectionEncryptionProvider decrypter = (ConnectionEncryptionProvider) decrypterCls.getDeclaredConstructor().newInstance();
                    password = decrypter.decrypt(password);
                }
                catch (Exception e)
                {
                    NucleusLogger.DATASTORE.warn("Error invoking decrypter class " + decrypterName, e);
                }
            }
        }
        return password;
    }

	/* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#isJdbcStore()
     */
    public boolean isJdbcStore()
    {
        return false;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getPersistenceHandler()
     */
    public StorePersistenceHandler getPersistenceHandler()
    {
        return persistenceHandler;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getFlushProcess()
     */
    public FlushProcess getFlushProcess()
    {
        if (flushProcess == null)
        {
            // Default to non-referential flush
            flushProcess = new FlushNonReferential();
        }
        return flushProcess;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getQueryManager()
     */
    public QueryManager getQueryManager()
    {
        if (queryMgr == null)
        {
            // Initialise support for queries
            queryMgr = new QueryManagerImpl(nucleusContext, this);
        }
        return queryMgr;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getMetaDataHandler()
     */
    public StoreSchemaHandler getSchemaHandler()
    {
        if (schemaHandler == null)
        {
            schemaHandler = new DefaultStoreSchemaHandler(this);
        }
        return schemaHandler;
    }

    public NamingFactory getNamingFactory()
    {
        if (namingFactory == null)
        {
            // Create the NamingFactory
            String namingFactoryName = getStringProperty(PropertyNames.PROPERTY_IDENTIFIER_NAMING_FACTORY);
            if ("datanucleus2".equalsIgnoreCase(namingFactoryName))
            {
                namingFactory = new DN2NamingFactory(nucleusContext);
            }
            else if ("jpa".equalsIgnoreCase(namingFactoryName) || "jakarta".equalsIgnoreCase(namingFactoryName))
            {
                namingFactory = new JPANamingFactory(nucleusContext);
            }
            else
            {
                // Fallback to the plugin mechanism
                String namingFactoryClassName = nucleusContext.getPluginManager().getAttributeValueForExtension("org.datanucleus.identifier_namingfactory", 
                    "name", namingFactoryName, "class-name");
                if (namingFactoryClassName == null)
                {
                    // TODO Localise this
                    throw new NucleusUserException("Error in specified NamingFactory " + namingFactoryName + " not found");
                }

                try
                {
                    Class[] argTypes = new Class[] {ClassConstants.NUCLEUS_CONTEXT};
                    Object[] args = new Object[] {nucleusContext};
                    namingFactory = (NamingFactory)nucleusContext.getPluginManager().createExecutableExtension("org.datanucleus.identifier_namingfactory", 
                        "name", namingFactoryName, "class-name", argTypes, args);
                }
                catch (Throwable thr)
                {
                    throw new NucleusUserException("Exception creating NamingFactory for datastore : " + thr.getMessage(), thr);
                }
            }

            // Set the case TODO Handle quoted cases (not specifiable via this property currently)
            String identifierCase = getStringProperty(PropertyNames.PROPERTY_IDENTIFIER_CASE);
            if (identifierCase != null)
            {
                if (identifierCase.equalsIgnoreCase("lowercase"))
                {
                    namingFactory.setNamingCase(NamingCase.LOWER_CASE);
                }
                else if (identifierCase.equalsIgnoreCase("UPPERCASE"))
                {
                    namingFactory.setNamingCase(NamingCase.UPPER_CASE);
                }
                else
                {
                    namingFactory.setNamingCase(NamingCase.MIXED_CASE);
                }
            }
        }

        return namingFactory;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getNucleusConnection(org.datanucleus.ExecutionContext)
     */
    public NucleusConnection getNucleusConnection(ExecutionContext ec)
    {
        // In <= 5.1.0.m3 this would have had last arg as "ec.getTransaction().isActive() ? ec.getTransaction() : null"
        ManagedConnection mc = connectionMgr.getConnection(ec.getTransaction().isActive(), ec, ec.getTransaction());

        // Lock the connection now that it is in use by the user
        mc.lock();

        Runnable closeRunnable = new Runnable()
        {
            public void run()
            {
                // Unlock the connection now that the user has finished with it
                mc.unlock();

                if (!ec.getTransaction().isActive())
                {
                    // Release the (unenlisted) connection (committing its statements)
                    mc.release();
                }
            }
        };
        return new NucleusConnectionImpl(mc.getConnection(), closeRunnable);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getValueGenerationManager()
     */
    public ValueGenerationManager getValueGenerationManager()
    {
        return valueGenerationMgr;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getApiAdapter()
     */
    public ApiAdapter getApiAdapter()
    {
        return nucleusContext.getApiAdapter();
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getStoreManagerKey()
     */
    public String getStoreManagerKey()
    {
        return storeManagerKey;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getNucleusContext()
     */   
    public PersistenceNucleusContext getNucleusContext()
    {
        return nucleusContext;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getMetaDataManager()
     */   
    public MetaDataManager getMetaDataManager()
    {
        return nucleusContext.getMetaDataManager();
    }

    // -------------------------------- Management of Classes --------------------------------

    public StoreData getStoreDataForClass(String className)
    {
        return storeDataMgr.get(className);
    }

    /**
     * Method to register some data with the store.
     * This will also register the data with the starter process.
     * @param data The StoreData to add
     */
    protected void registerStoreData(StoreData data)
    {
        storeDataMgr.registerStoreData(data);

        // Keep the AutoStarter in step with our managed classes/fields
        if (nucleusContext.getAutoStartMechanism() != null)
        {
            nucleusContext.getAutoStartMechanism().addClass(data);
        }
    }

    /**
     * Method to deregister all existing store data so that we are managing nothing.
     */
    protected void deregisterAllStoreData()
    {
        storeDataMgr.clear();

        // Keep the AutoStarter in step with our managed classes/fields
        AutoStartMechanism starter = nucleusContext.getAutoStartMechanism();
        if (starter != null)
        {
            try
            {
                if (!starter.isOpen())
                {
                    starter.open();
                }
                starter.deleteAllClasses();
            }
            finally
            {
                if (starter.isOpen())
                {
                    starter.close();
                }
            }
        }
    }

    /**
     * Convenience method to log the configuration of this store manager.
     */
    protected void logConfiguration()
    {
        if (NucleusLogger.DATASTORE.isDebugEnabled())
        {
            NucleusLogger.DATASTORE.debug("======================= Datastore =========================");
            NucleusLogger.DATASTORE.debug("StoreManager : \"" + storeManagerKey + "\" (" + getClass().getName() + ")");

            NucleusLogger.DATASTORE.debug("Datastore : " +
                (getBooleanProperty(PropertyNames.PROPERTY_DATASTORE_READONLY) ? "read-only" : "read-write") + 
                (getBooleanProperty(PropertyNames.PROPERTY_SERIALIZE_READ) ? ", useLocking" : ""));

            // Schema : Auto-Create/Validate
            StringBuilder autoCreateOptions = null;
            if (getSchemaHandler().isAutoCreateTables() || getSchemaHandler().isAutoCreateColumns() || getSchemaHandler().isAutoCreateConstraints())
            {
                autoCreateOptions = new StringBuilder();
                boolean first = true;
                if (getSchemaHandler().isAutoCreateTables())
                {
                    if (!first)
                    {
                        autoCreateOptions.append(",");
                    }
                    autoCreateOptions.append("Tables");
                    first = false;
                }
                if (getSchemaHandler().isAutoCreateColumns())
                {
                    if (!first)
                    {
                        autoCreateOptions.append(",");
                    }
                    autoCreateOptions.append("Columns");
                    first = false;
                }
                if (getSchemaHandler().isAutoCreateConstraints())
                {
                    if (!first)
                    {
                        autoCreateOptions.append(",");
                    }
                    autoCreateOptions.append("Constraints");
                    first = false;
                }
            }
            StringBuilder validateOptions = null;
            if (getSchemaHandler().isValidateTables() || getSchemaHandler().isValidateColumns() || getSchemaHandler().isValidateConstraints())
            {
                validateOptions = new StringBuilder();
                boolean first = true;
                if (getSchemaHandler().isValidateTables())
                {
                    validateOptions.append("Tables");
                    first = false;
                }
                if (getSchemaHandler().isValidateColumns())
                {
                    if (!first)
                    {
                        validateOptions.append(",");
                    }
                    validateOptions.append("Columns");
                    first = false;
                }
                if (getSchemaHandler().isValidateConstraints())
                {
                    if (!first)
                    {
                        validateOptions.append(",");
                    }
                    validateOptions.append("Constraints");
                    first = false;
                }
            }
            NucleusLogger.DATASTORE.debug("Schema Control : " +
                "AutoCreate(" + (autoCreateOptions != null ? autoCreateOptions.toString() : "None") + ")" +
                ", Validate(" + (validateOptions != null ? validateOptions.toString() : "None") + ")");

            String namingFactoryName = getStringProperty(PropertyNames.PROPERTY_IDENTIFIER_NAMING_FACTORY);
            String namingCase = getStringProperty(PropertyNames.PROPERTY_IDENTIFIER_CASE);
            NucleusLogger.DATASTORE.debug("Schema : NamingFactory=" + namingFactoryName + " identifierCase=" + namingCase);

            NucleusLogger.DATASTORE.debug("Query Languages : " + StringUtils.collectionToString(getSupportedQueryLanguages()));
            NucleusLogger.DATASTORE.debug("Queries : Timeout=" + getIntProperty(PropertyNames.PROPERTY_DATASTORE_READ_TIMEOUT));

            NucleusLogger.DATASTORE.debug("===========================================================");
        }
    }

    /**
     * Method to output the information about the StoreManager.
     * Supports the category "DATASTORE".
     */
    public void printInformation(String category, PrintStream ps)
    throws Exception
    {
        if (category.equalsIgnoreCase("DATASTORE"))
        {
            ps.println(Localiser.msg("032020", storeManagerKey, getConnectionURL(), getBooleanProperty(PropertyNames.PROPERTY_DATASTORE_READONLY) ? "read-only" : "read-write"));
        }
    }

    // ------------------------------- Class Management -----------------------------------

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#managesClass(java.lang.String)
     */
    public boolean managesClass(String className)
    {
        return storeDataMgr.managesClass(className);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#addClasses(org.datanucleus.ClassLoaderResolver, java.lang.String[])
     */
    public void manageClasses(ClassLoaderResolver clr, String... classNames)
    {
        if (classNames == null)
        {
            return;
        }

        // Filter out any "simple" type classes
        String[] filteredClassNames = getNucleusContext().getTypeManager().filterOutSupportedSecondClassNames(classNames);

        // Find the ClassMetaData for these classes and all referenced by these classes
        List<AbstractClassMetaData> refClasses = getMetaDataManager().getReferencedClasses(filteredClassNames, clr);
        for (AbstractClassMetaData cmd : refClasses)
        {
            if (cmd.getPersistenceModifier() == ClassPersistenceModifier.PERSISTENCE_CAPABLE)
            {
                if (!storeDataMgr.managesClass(cmd.getFullClassName()))
                {
                    registerStoreData(newStoreData((ClassMetaData) cmd, clr));
                }
            }
        }
    }

    /**
     * Instantiate a StoreData instance using the provided ClassMetaData and ClassLoaderResolver. 
     * Override this method if you want to instantiate a subclass of StoreData.
     * @param cmd MetaData for the class
     * @param clr ClassLoader resolver
     * @return The StoreData
     */
    protected StoreData newStoreData(ClassMetaData cmd, ClassLoaderResolver clr) 
    {
        return new StoreData(cmd.getFullClassName(), cmd, StoreData.Type.FCO, null);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#removeAllClasses(org.datanucleus.ClassLoaderResolver)
     */
    public void unmanageAllClasses(ClassLoaderResolver clr)
    {
        // Do nothing.
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#unmanageClass(org.datanucleus.ClassLoaderResolver, java.lang.String, boolean)
     */
    @Override
    public void unmanageClass(ClassLoaderResolver clr, String className, boolean removeFromDatastore)
    {
        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(className, clr);
        if (cmd.getPersistenceModifier() == ClassPersistenceModifier.PERSISTENCE_CAPABLE)
        {
            if (removeFromDatastore)
            {
                // TODO Handle this
            }

            // Remove our knowledge of this class
            // TODO Remove any fields that are registered in their own right (join tables)
            storeDataMgr.deregisterClass(className);
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#manageClassForIdentity(java.lang.Object, org.datanucleus.ClassLoaderResolver)
     */
    public String manageClassForIdentity(Object id, ClassLoaderResolver clr)
    {
        if (nucleusContext.getTypeManager().isSupportedSecondClassType(id.getClass().getName()))
        {
            return null;
        }

        String className = IdentityUtils.getTargetClassNameForIdentity(id);
        if (className == null)
        {
            // Just return since class name unknown
            return null;
        }

        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(className, clr);

        // Basic error checking
        if (IdentityUtils.isDatastoreIdentity(id) && cmd.getIdentityType() != IdentityType.DATASTORE)
        {
            throw new NucleusUserException(Localiser.msg("002004", id, cmd.getFullClassName()));
        }
        if (IdentityUtils.isSingleFieldIdentity(id) && (cmd.getIdentityType() != IdentityType.APPLICATION || !cmd.getObjectidClass().equals(id.getClass().getName())))
        {
            throw new NucleusUserException(Localiser.msg("002004", id, cmd.getFullClassName()));
        }

        // If the class is not yet managed, manage it
        if (!managesClass(className))
        {
            manageClasses(clr, className);
        }

        return className;
    }

    // ------------------------------ PersistenceManager interface -----------------------------------

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getExtent(org.datanucleus.ExecutionContext, java.lang.Class, boolean)
     */
    public <T> Extent<T> getExtent(ExecutionContext ec, Class<T> c, boolean subclasses)
    {
        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(c, ec.getClassLoaderResolver());
        if (!cmd.isRequiresExtent())
        {
            throw new NoExtentException(c.getName());
        }

        // Create Extent using JDOQL query
        if (!managesClass(c.getName()))
        {
            manageClasses(ec.getClassLoaderResolver(), c.getName());
        }
        return new DefaultCandidateExtent(ec, c, subclasses, cmd);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getSupportedQueryLanguages()
     */
    @Override
    public Collection<String> getSupportedQueryLanguages()
    {
        // All StoreManagers should support a minimum of JDOQL/JPQL
        Collection<String> languages = new HashSet<>();
        languages.add(Query.LANGUAGE_JDOQL);
        languages.add(Query.LANGUAGE_JPQL);
        return languages;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#supportsQueryLanguage(java.lang.String)
     */
    @Override
    public boolean supportsQueryLanguage(String language)
    {
        // All StoreManagers should support a minimum of JDOQL/JPQL
        return (language != null && (language.equalsIgnoreCase(Query.LANGUAGE_JDOQL) || language.equalsIgnoreCase(Query.LANGUAGE_JPQL)));
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getClassNameForObjectID(java.lang.Object, org.datanucleus.ClassLoaderResolver, org.datanucleus.ExecutionContext)
     */
    public String getClassNameForObjectID(Object id, ClassLoaderResolver clr, ExecutionContext ec)
    {
        if (id == null)
        {
            // User stupidity
            return null;
        }
        else if (id instanceof SCOID)
        {
            // Object is a SCOID
            return ((SCOID) id).getSCOClass();
        }

        String className = IdentityUtils.getTargetClassNameForIdentity(id);
        if (className != null)
        {
            return className;
        }

        // Application identity with user PK class, so find all using this PK
        Collection<AbstractClassMetaData> cmds = getMetaDataManager().getClassMetaDataWithApplicationId(id.getClass().getName());
        if (cmds != null && !cmds.isEmpty())
        {
            // Just return the class name of the first one using this id - could be any in this tree
            return cmds.iterator().next().getFullClassName();
        }

        return null;
    }

    /**
     * Accessor for whether this value strategy is supported.
     * @param strategy The strategy
     * @return Whether it is supported.
     */
    public boolean supportsValueGenerationStrategy(String strategy)
    {
        return valueGenerationMgr.supportsStrategy(strategy);
    }

    /**
     * Convenience method to return whether the strategy used by the specified class/member is generated
     * by the datastore (during a persist).
     * @param cmd Metadata for the class
     * @param absFieldNumber Absolute field number for the field (or -1 if datastore id)
     * @return Whether the strategy is generated in the datastore
     */
    public boolean isValueGenerationStrategyDatastoreAttributed(AbstractClassMetaData cmd, int absFieldNumber)
    {
        if (absFieldNumber < 0)
        {
            if (cmd.isEmbeddedOnly())
            {
                return false;
            }

            // Datastore-id
            DatastoreIdentityMetaData idmd = cmd.getBaseDatastoreIdentityMetaData();
            if (idmd == null)
            {
                // native
                String strategy = getValueGenerationStrategyForNative(cmd);
                if (strategy.equalsIgnoreCase(ValueGenerationStrategy.IDENTITY.toString()))
                {
                    return true;
                }
            }
            else
            {
                ValueGenerationStrategy idStrategy = idmd.getValueStrategy();
                if (idStrategy == ValueGenerationStrategy.IDENTITY)
                {
                    return true;
                }
                else if (idStrategy == ValueGenerationStrategy.NATIVE)
                {
                    String strategy = getValueGenerationStrategyForNative(cmd);
                    if (strategy.equalsIgnoreCase(ValueGenerationStrategy.IDENTITY.toString()))
                    {
                        return true;
                    }
                }
            }
        }
        else
        {
            // Value generation for a member
            AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(absFieldNumber);
            if (mmd.getValueStrategy() == null)
            {
                return false;
            }
            else if (mmd.getValueStrategy() == ValueGenerationStrategy.IDENTITY)
            {
                return true;
            }
            else if (mmd.getValueStrategy() == ValueGenerationStrategy.NATIVE)
            {
                String strategy = getValueGenerationStrategyForNative(mmd);
                if (strategy.equalsIgnoreCase(ValueGenerationStrategy.IDENTITY.toString()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Object getValueGenerationStrategyValue(ExecutionContext ec, AbstractClassMetaData cmd, AbstractMemberMetaData mmd)
    {
        // Get the ValueGenerator for this member
        ValueGenerator generator = getValueGeneratorForMember(ec.getClassLoaderResolver(), cmd, mmd);

        // Get the next value from the ValueGenerator
        Object value = getNextValueForValueGenerator(generator, ec);

        // Do any necessary conversion of the value to the precise member type
        if (mmd != null)
        {
            try
            {
                Object convertedValue = TypeConversionHelper.convertTo(value, mmd.getType());
                if (convertedValue == null)
                {
                    throw new NucleusException(Localiser.msg("040013", mmd.getFullFieldName(), value)).setFatal();
                }
                value = convertedValue;
            }
            catch (NumberFormatException nfe)
            {
                throw new NucleusUserException("Value strategy created value="+value+" type="+value.getClass().getName() +
                        " but field is of type " + mmd.getTypeName() + ". Use a different strategy or change the type of the field " + mmd.getFullFieldName());
            }
        }

        if (NucleusLogger.VALUEGENERATION.isDebugEnabled())
        {
            String fieldName = null;
            ValueGenerationStrategy strategy = null;
            if (mmd != null)
            {
                // real field
                fieldName = mmd.getFullFieldName();
                strategy = mmd.getValueStrategy();
            }
            else
            {
                // datastore-identity surrogate field
                fieldName = cmd.getFullClassName() + " (datastore id)";
                strategy = cmd.getDatastoreIdentityMetaData().getValueStrategy();
            }
            NucleusLogger.VALUEGENERATION.debug(Localiser.msg("040012", fieldName, strategy, generator.getClass().getName(), value));
        }

        return value;
    }

    protected synchronized ValueGenerator getValueGeneratorForMember(ClassLoaderResolver clr, AbstractClassMetaData cmd, AbstractMemberMetaData mmd)
    {
        // Check if we have a ValueGenerator already created for this member
        String memberKey = (mmd != null) ? valueGenerationMgr.getMemberKey(mmd) : valueGenerationMgr.getMemberKey(cmd);
        ValueGenerator generator = valueGenerationMgr.getValueGeneratorForMemberKey(memberKey);
        if (generator != null)
        {
            // Return the ValueGenerator already registered against this member "key"
            return generator;
        }

        // No ValueGenerator registered for this memberKey, so need to determine which to use and create it as required.
        String memberName = null; // Used for logging
        ValueGenerationStrategy strategy = null;
        String sequence = null;
        String valueGeneratorName = null;
        if (mmd != null)
        {
            // real field
            memberName = mmd.getFullFieldName();
            strategy = mmd.getValueStrategy();
            if (strategy.equals(ValueGenerationStrategy.NATIVE))
            {
                strategy = ValueGenerationStrategy.getIdentityStrategy(getValueGenerationStrategyForNative(mmd));
            }
            sequence = mmd.getSequence();
            valueGeneratorName = mmd.getValueGeneratorName();
        }
        else
        {
            // datastore-identity surrogate field
            memberName = cmd.getFullClassName() + " (datastore id)";
            strategy = cmd.getDatastoreIdentityMetaData().getValueStrategy();
            if (strategy.equals(ValueGenerationStrategy.NATIVE))
            {
                strategy = ValueGenerationStrategy.getIdentityStrategy(getValueGenerationStrategyForNative(cmd));
            }
            sequence = cmd.getDatastoreIdentityMetaData().getSequence();
            valueGeneratorName = cmd.getDatastoreIdentityMetaData().getValueGeneratorName();
        }

        // Check for the strategyName being a known "unique" ValueGenerator, and register it if not yet done
        String strategyName = strategy.equals(ValueGenerationStrategy.CUSTOM) ? strategy.getCustomName() : strategy.toString();
        generator = valueGenerationMgr.getUniqueValueGeneratorByName(strategyName);
        if (generator != null)
        {
            // "unique" ValueGenerator already defined for this strategy, so register it against the member
            valueGenerationMgr.registerValueGeneratorForMemberKey(memberKey, generator);
            return generator;
        }

        // Must be "datastore" specific generator so use plugin mechanism to create one and register against this member "key"
        if (!strategy.toString().equalsIgnoreCase("custom") && !supportsValueGenerationStrategy(strategy.toString()))
        {
            throw new NucleusUserException("Attempt to use strategy=" + strategy.toString() + " but this is not supported for this datastore");
        }

        // Set up the default properties available for all value generators
        // Extract any metadata-based generation information keyed by the "valueGeneratorName"
        TableGeneratorMetaData tableGeneratorMetaData = null;
        SequenceMetaData sequenceMetaData = null;
        if (valueGeneratorName != null)
        {
            if (strategy == ValueGenerationStrategy.INCREMENT)
            {
                tableGeneratorMetaData = getMetaDataManager().getMetaDataForTableGenerator(clr, valueGeneratorName);
                if (tableGeneratorMetaData == null)
                {
                    throw new NucleusUserException(Localiser.msg("040014", memberName, valueGeneratorName));
                }
            }
            else if (strategy == ValueGenerationStrategy.SEQUENCE)
            {
                sequenceMetaData = getMetaDataManager().getMetaDataForSequence(clr, valueGeneratorName);
                if (sequenceMetaData == null)
                {
                    throw new NucleusUserException(Localiser.msg("040015", memberName, valueGeneratorName));
                }
            }
        }
        else if (strategy == ValueGenerationStrategy.SEQUENCE && sequence != null)
        {
            // TODO Allow for package name of this class prefix for the sequence name
            sequenceMetaData = getMetaDataManager().getMetaDataForSequence(clr, sequence);
            if (sequenceMetaData == null)
            {
                // No <sequence> defining the datastore sequence name, so fallback to this name directly in the datastore
                NucleusLogger.VALUEGENERATION.info("Member " + memberName + " has been specified to use sequence '" + sequence +
                        "' but there is no <sequence> specified in the MetaData. Falling back to use a sequence in the datastore with this name directly.");
            }
        }

        Properties props = getPropertiesForValueGenerator(cmd, mmd != null ? mmd.getAbsoluteFieldNumber() : -1, clr, sequenceMetaData, tableGeneratorMetaData);

        return valueGenerationMgr.createAndRegisterValueGenerator(memberKey, strategyName, props);
    }

    /**
     * Method defining which value-strategy to use when the user specifies "native" on datastore-identity. 
     * This will return as follows
     * <ul>
     * <li>If your field is Numeric-based (or no jdbc-type) then chooses the first one that is supported of "identity", "sequence", "increment", otherwise exception.</li>
     * <li>Otherwise your field is String-based then chooses "uuid-hex".</li>
     * </ul>
     * If your store plugin requires something else then override this
     * @param cmd Class requiring the strategy
     * @return The strategy used when "native" is specified
     */
    public String getValueGenerationStrategyForNative(AbstractClassMetaData cmd)
    {
        DatastoreIdentityMetaData idmd = cmd.getBaseDatastoreIdentityMetaData();
        if (idmd != null && idmd.getColumnMetaData() != null)
        {
            if (MetaDataUtils.isJdbcTypeString(idmd.getColumnMetaData().getJdbcType()))
            {
                return ValueGenerationStrategy.UUIDHEX.toString();
            }
        }

        // Numeric datastore-identity
        if (supportsValueGenerationStrategy(ValueGenerationStrategy.IDENTITY.toString()))
        {
            return ValueGenerationStrategy.IDENTITY.toString();
        }
        else if (supportsValueGenerationStrategy(ValueGenerationStrategy.SEQUENCE.toString()) && idmd != null && idmd.getSequence() != null)
        {
            return ValueGenerationStrategy.SEQUENCE.toString();
        }
        else if (supportsValueGenerationStrategy(ValueGenerationStrategy.INCREMENT.toString()))
        {
            return ValueGenerationStrategy.INCREMENT.toString();
        }
        throw new NucleusUserException("This datastore provider doesn't support numeric native strategy for class " + cmd.getFullClassName());
    }

    /**
     * Method defining which value-strategy to use when the user specifies "native" on a member. 
     * This will return as follows
     * <ul>
     * <li>If your field is Numeric-based then chooses the first one that is supported of "identity", "sequence", "increment", otherwise exception.</li>
     * <li>Otherwise your field is String-based then chooses "uuid-hex".</li>
     * </ul>
     * If your store plugin requires something else then override this
     * @param mmd Member requiring the strategy
     * @return The strategy used when "native" is specified
     */
    public String getValueGenerationStrategyForNative(AbstractMemberMetaData mmd)
    {
        Class type = mmd.getType();
        if (String.class.isAssignableFrom(type))
        {
            return ValueGenerationStrategy.UUIDHEX.toString(); // TODO Do we really want this when we have "uuid"?
        }
        else if (type == Long.class || type == Integer.class || type == Short.class || type == long.class || type == int.class || type == short.class || type== BigInteger.class)
        {
            if (supportsValueGenerationStrategy(ValueGenerationStrategy.IDENTITY.toString()))
            {
                return ValueGenerationStrategy.IDENTITY.toString();
            }
            else if (supportsValueGenerationStrategy(ValueGenerationStrategy.SEQUENCE.toString()) && mmd.getSequence() != null)
            {
                return ValueGenerationStrategy.SEQUENCE.toString();
            }
            else if (supportsValueGenerationStrategy(ValueGenerationStrategy.INCREMENT.toString()))
            {
                return ValueGenerationStrategy.INCREMENT.toString();
            }
            throw new NucleusUserException("This datastore provider doesn't support numeric native strategy for member " + mmd.getFullFieldName());
        }

        throw new NucleusUserException("This datastore provider doesn't support native strategy for field of type " + type.getName());
    }

    /**
     * Accessor for the next value from the specified ValueGenerator.
     * This implementation simply returns generator.next(). Any case where the generator requires datastore connections should override this method.
     * @param generator The generator
     * @param ec execution context
     * @return The next value.
     */
    protected Object getNextValueForValueGenerator(ValueGenerator generator, final ExecutionContext ec)
    {
        Object value = null;
        synchronized (generator)
        {
            // Get the next value for this generator for this ExecutionContext
            // Note : this is synchronised since we don't want to risk handing out this generator while its connectionProvider is set to that of a different ExecutionContext
            // It maybe would be good to change ValueGenerator to have a next taking the connectionProvider
            if (generator instanceof AbstractConnectedGenerator)
            {
                // datastore-based generator so set the connection provider, using connection for PM
                ValueGenerationConnectionProvider connProvider = new ValueGenerationConnectionProvider()
                {
                    ManagedConnection mconn;
                    public ManagedConnection retrieveConnection()
                    {
                        mconn = connectionMgr.getConnection(ec);
                        return mconn;
                    }
                    public void releaseConnection() 
                    {
                        mconn.release();
                        mconn = null;
                    }
                };
                ((AbstractConnectedGenerator)generator).setConnectionProvider(connProvider);
            }

            value = generator.next();
        }
        return value;
    }

    /**
     * Method to return the properties to pass to the generator for the specified field.
     * Will define the following properties "class-name", "root-class-name", "field-name" (if for a field),
     * "sequence-name", "key-initial-value", "key-cache-size", "sequence-table-name", "sequence-schema-name",
     * "sequence-catalog-name", "sequence-name-column-name", "sequence-nextval-column-name".
     * In addition any extension properties on the respective field or datastore-identity are also passed through as properties.
     * @param cmd MetaData for the class
     * @param absoluteFieldNumber Number of the field (-1 = datastore identity)
     * @param clr ClassLoader resolver
     * @param seqmd Any sequence metadata
     * @param tablegenmd Any table generator metadata
     * @return The properties to use for this field
     */
    protected Properties getPropertiesForValueGenerator(AbstractClassMetaData cmd, int absoluteFieldNumber, ClassLoaderResolver clr, SequenceMetaData seqmd, TableGeneratorMetaData tablegenmd)
    {
        // Set up the default properties available for all value generators
        Properties properties = new Properties();
        properties.setProperty(ValueGenerator.PROPERTY_CLASS_NAME, cmd.getFullClassName());
        properties.put(ValueGenerator.PROPERTY_ROOT_CLASS_NAME, cmd.getBaseAbstractClassMetaData().getFullClassName());

        AbstractMemberMetaData mmd = null;
        ValueGenerationStrategy strategy = null;
        String sequence = null;
        Map<String, String> extensions = null;
        if (absoluteFieldNumber >= 0)
        {
            // real field
            mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(absoluteFieldNumber);
            strategy = mmd.getValueStrategy();
            if (strategy.equals(ValueGenerationStrategy.NATIVE))
            {
                strategy = ValueGenerationStrategy.getIdentityStrategy(getValueGenerationStrategyForNative(mmd));
            }

            sequence = mmd.getSequence();
            if (sequence != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME, sequence);
            }
            extensions = mmd.getExtensions();
            if (extensions != null)
            {
                properties.putAll(extensions);
            }
            properties.setProperty(ValueGenerator.PROPERTY_FIELD_NAME, mmd.getFullFieldName());
        }
        else
        {
            // datastore-identity surrogate field
            // always use the root IdentityMetaData since the root class defines the identity
            DatastoreIdentityMetaData idmd = cmd.getBaseDatastoreIdentityMetaData();
            strategy = idmd.getValueStrategy();
            if (strategy.equals(ValueGenerationStrategy.NATIVE))
            {
                strategy = ValueGenerationStrategy.getIdentityStrategy(getValueGenerationStrategyForNative(cmd));
            }

            sequence = idmd.getSequence();
            if (sequence != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME, sequence);
            }
            extensions = idmd.getExtensions();
            if (extensions != null)
            {
                properties.putAll(extensions);
            }
        }

        if (strategy == ValueGenerationStrategy.INCREMENT)
        {
            addValueGenerationPropertiesForIncrement(properties, tablegenmd);
        }
        else if (strategy == ValueGenerationStrategy.SEQUENCE)
        {
            addValueGenerationPropertiesForSequence(properties, seqmd);
        }
        return properties;
    }

    protected void addValueGenerationPropertiesForIncrement(Properties properties, TableGeneratorMetaData tablegenmd)
    {
        if (tablegenmd != null)
        {
            // User has specified a TableGenerator (JPA)
            properties.setProperty(ValueGenerator.PROPERTY_KEY_INITIAL_VALUE, "" + tablegenmd.getInitialValue());
            properties.setProperty(ValueGenerator.PROPERTY_KEY_CACHE_SIZE, "" + tablegenmd.getAllocationSize());

            if (tablegenmd.getTableName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_TABLE, tablegenmd.getTableName());
            }
            if (tablegenmd.getCatalogName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_CATALOG, tablegenmd.getCatalogName());
            }
            if (tablegenmd.getSchemaName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_SCHEMA, tablegenmd.getSchemaName());
            }
            if (tablegenmd.getPKColumnName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_NAME_COLUMN, tablegenmd.getPKColumnName());
            }
            if (tablegenmd.getValueColumnName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_NEXTVAL_COLUMN, tablegenmd.getValueColumnName());
            }
            if (tablegenmd.getPKColumnValue() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME, tablegenmd.getPKColumnValue());
            }
        }
        else
        {
            // Set some defaults
            if (!properties.containsKey(ValueGenerator.PROPERTY_KEY_CACHE_SIZE))
            {
                // Use default allocation size
                properties.setProperty(ValueGenerator.PROPERTY_KEY_CACHE_SIZE, "" + getIntProperty(PropertyNames.PROPERTY_VALUEGEN_INCREMENT_ALLOCSIZE));
            }
        }
    }

    protected void addValueGenerationPropertiesForSequence(Properties properties, SequenceMetaData seqmd)
    {
        if (seqmd != null)
        {
            // User has specified a SequenceGenerator (JDO/JPA)
            if (seqmd.getSchemaName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_SCHEMA, seqmd.getSchemaName());
            }
            if (seqmd.getCatalogName() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCETABLE_CATALOG, seqmd.getCatalogName());
            }
            if (seqmd.getDatastoreSequence() != null)
            {
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME, "" + seqmd.getDatastoreSequence());
            }
            else if (StringUtils.isWhitespace(properties.getProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME)) && seqmd.getName() != null)
            {
                // Apply default to sequence name, as name of sequence metadata
                properties.setProperty(ValueGenerator.PROPERTY_SEQUENCE_NAME, seqmd.getName());
            }
            if (seqmd.getInitialValue() >= 0)
            {
                properties.setProperty(ValueGenerator.PROPERTY_KEY_INITIAL_VALUE, "" + seqmd.getInitialValue());
            }
            if (seqmd.getAllocationSize() > 0)
            {
                properties.setProperty(ValueGenerator.PROPERTY_KEY_CACHE_SIZE, "" + seqmd.getAllocationSize());
            }
            else
            {
                // Use default allocation size
                properties.setProperty(ValueGenerator.PROPERTY_KEY_CACHE_SIZE, "" + getIntProperty(PropertyNames.PROPERTY_VALUEGEN_SEQUENCE_ALLOCSIZE));
            }

            // Add on any extensions specified on the sequence
            Map<String, String> seqExtensions = seqmd.getExtensions();
            if (seqExtensions != null)
            {
                properties.putAll(seqExtensions);
            }
        }
        else
        {
            // Set some defaults
            if (!properties.containsKey(ValueGenerator.PROPERTY_KEY_CACHE_SIZE))
            {
                // Use default allocation size
                properties.setProperty(ValueGenerator.PROPERTY_KEY_CACHE_SIZE, "" + getIntProperty(PropertyNames.PROPERTY_VALUEGEN_INCREMENT_ALLOCSIZE));
            }
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getSubClassesForClass(java.lang.String, boolean, org.datanucleus.ClassLoaderResolver)
     */
    public Collection<String> getSubClassesForClass(String className, boolean includeDescendents, ClassLoaderResolver clr)
    {
        Collection<String> subclasses = new HashSet<>();

        String[] subclassNames = getMetaDataManager().getSubclassesForClass(className, includeDescendents);
        if (subclassNames != null)
        {
            // Load up the table for any classes that are not yet loaded
            for (int i=0;i<subclassNames.length;i++)
            {
                if (!storeDataMgr.managesClass(subclassNames[i]))
                {
                    // We have no knowledge of this class so load it now
                    manageClasses(clr, subclassNames[i]);
                }
                subclasses.add(subclassNames[i]);
            }
        }

        return subclasses;
    }

    /**
     * Accessor for the supported options in string form.
     * Typical values specified here are :-
     * <ul>
     * <li>ApplicationIdentity - if the datastore supports application identity</li>
     * <li>DatastoreIdentity - if the datastore supports datastore identity</li>
     * <li>ORM - if the datastore supports (some) ORM concepts</li>
     * <li>TransactionIsolationLevel.read-committed - if supporting this txn isolation level</li>
     * <li>TransactionIsolationLevel.read-uncommitted - if supporting this txn isolation level</li>
     * <li>TransactionIsolationLevel.repeatable-read - if supporting this txn isolation level</li>
     * <li>TransactionIsolationLevel.serializable - if supporting this txn isolation level</li>
     * <li>TransactionIsolationLevel.snapshot - if supporting this txn isolation level</li>
     * <li>Query.Cancel - if supporting cancelling of queries</li>
     * <li>Query.Timeout - if supporting timeout of queries</li>
     * </ul>
     */
    public Collection<String> getSupportedOptions()
    {
        return Collections.EMPTY_SET;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#hasProperty(java.lang.String)
     */
    @Override
    public boolean hasProperty(String name)
    {
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return true;
        }
        return nucleusContext.getConfiguration().hasProperty(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.TypedPropertyStore#getProperty(java.lang.String)
     */
    @Override
    public Object getProperty(String name)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getProperty(name);
        }
        return nucleusContext.getConfiguration().getProperty(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#getIntProperty(java.lang.String)
     */
    @Override
    public int getIntProperty(String name)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getIntProperty(name);
        }
        return nucleusContext.getConfiguration().getIntProperty(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#getStringProperty(java.lang.String)
     */
    @Override
    public String getStringProperty(String name)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getStringProperty(name);
        }
        return nucleusContext.getConfiguration().getStringProperty(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#getBooleanProperty(java.lang.String)
     */
    @Override
    public boolean getBooleanProperty(String name)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getBooleanProperty(name);
        }
        return nucleusContext.getConfiguration().getBooleanProperty(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#getBooleanProperty(java.lang.String, boolean)
     */
    @Override
    public boolean getBooleanProperty(String name, boolean resultIfNotSet)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getBooleanProperty(name, resultIfNotSet);
        }
        return nucleusContext.getConfiguration().getBooleanProperty(name, resultIfNotSet);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.properties.PropertyStore#getBooleanObjectProperty(java.lang.String)
     */
    @Override
    public Boolean getBooleanObjectProperty(String name)
    {
        // Use local property value if present, otherwise relay back to context property value
        if (properties.containsKey(name.toLowerCase(Locale.ENGLISH)))
        {
            return super.getBooleanObjectProperty(name);
        }
        return nucleusContext.getConfiguration().getBooleanObjectProperty(name);
    }

    @Override
    public void enableSchemaGeneration()
    {
        schemaHandler.enableSchemaGeneration();
    }

    @Override
    public void resetSchemaGeneration()
    {
        schemaHandler.resetSchemaGeneration();
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#useBackedSCOWrapperForMember(org.datanucleus.metadata.AbstractMemberMetaData, org.datanucleus.store.ExecutionContext)
     */
    public boolean useBackedSCOWrapperForMember(AbstractMemberMetaData mmd, ExecutionContext ec)
    {
        return usesBackedSCOWrappers();
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.StoreManager#getDefaultStateManagerClassName()
     */
    public String getDefaultStateManagerClassName()
    {
        return StateManagerImpl.class.getName();
    }
}