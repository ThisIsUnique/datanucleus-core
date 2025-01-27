/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
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
    ...
**********************************************************************/
package org.datanucleus.store.fieldmanager;

import java.util.Set;

import org.datanucleus.ExecutionContext;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.types.TypeManager;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Field manager that runs reachability on all PC objects referenced from the source object.
 * Whenever a PC object is encountered it recurses to that object, and so on.
 * Provides the basis for the JDO feature "persistence-by-reachability-at-commit".
 */
public class ReachabilityFieldManager extends AbstractFieldManager
{
    /** StateManager for the owning object. */
    private final DNStateManager sm;

    /** Set of reachables up to this point. */
    private Set reachables = null;

    /**
     * Constructor.
     * @param sm StateManager for the object.
     * @param reachables Reachables up to this point
     */
    public ReachabilityFieldManager(DNStateManager sm, Set reachables)
    {
        this.sm = sm;
        this.reachables = reachables;
    }

    /**
     * Utility method to process the passed persistable object.
     * @param obj The persistable object
     * @param mmd MetaData for the member storing this object
     */
    protected void processPersistable(Object obj, AbstractMemberMetaData mmd)
    {
        ExecutionContext ec = sm.getExecutionContext();
        DNStateManager objSM = ec.findStateManager(obj);
        if (objSM != null)
        {
            Object objID = objSM.getInternalObjectId();
            if (!reachables.contains(objID) && !objSM.isDeleted())
            {
                if (ec.isEnlistedInTransaction(objID))
                {
                    // This object was enlisted so make sure all of its fields are loaded before continuing
                    objSM.loadUnloadedRelationFields();
                }

                // Add this object id since not yet reached
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("007000", IdentityUtils.getPersistableIdentityForId(objID), objSM.getLifecycleState()));
                }
                reachables.add(objID);

                // Recurse through relation fields of this object
                ReachabilityFieldManager pcFM = new ReachabilityFieldManager(objSM, reachables);
                int[] relationFieldNums = objSM.getClassMetaData().getRelationMemberPositions(ec.getClassLoaderResolver());
                int[] loadedFieldNumbers = ClassUtils.getFlagsSetTo(objSM.getLoadedFields(), relationFieldNums, true);
                if (loadedFieldNumbers != null && loadedFieldNumbers.length > 0)
                {
                    objSM.provideFields(loadedFieldNumbers, pcFM);
                }
            }
        }
        else
        {
            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("007005", sm.getExecutionContext().getApiAdapter().getIdForObject(obj), mmd.getFullFieldName()));
            }
        }
    }
    
    private void processContainer(int fieldNumber, Object container, AbstractMemberMetaData mmd)
    {
        // Process all objects of the Container that are PC
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("007002", mmd.getFullFieldName()));
        }
        
        ApiAdapter api = sm.getExecutionContext().getApiAdapter();
        TypeManager typeManager = sm.getExecutionContext().getTypeManager();
        for (Object object : typeManager.getContainerAdapter(container))
        {
            if (api.isPersistable(object))
            {
                processPersistable(object, mmd);
            }    
        }
    }

    /**
     * Method to store an object field.
     * @param fieldNumber Number of the field (absolute)
     * @param value Value of the field
     */
    public void storeObjectField(int fieldNumber, Object value)
    {
        AbstractMemberMetaData mmd = sm.getClassMetaData().getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber);
        if (value != null)
        {
            boolean persistCascade = mmd.isCascadePersist();
            
            if (persistCascade)
            {
                RelationType relType = mmd.getRelationType(sm.getExecutionContext().getClassLoaderResolver());
                if ( relType != RelationType.NONE ){
                    
                    if ( mmd.hasContainer() )
                    {
                        processContainer(fieldNumber, value, mmd);
                    }
                    else
                    {
                        // Process PC fields
                        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                        {
                            NucleusLogger.PERSISTENCE.debug(Localiser.msg("007004", mmd.getFullFieldName()));
                        }
                        
                        processPersistable(value, mmd);
                    }
                }
            }
        }
    }

    public void storeBooleanField(int fieldNumber, boolean value)
    {
        // Do nothing
    }

    public void storeByteField(int fieldNumber, byte value)
    {
        // Do nothing
    }

    public void storeCharField(int fieldNumber, char value)
    {
        // Do nothing
    }

    public void storeDoubleField(int fieldNumber, double value)
    {
        // Do nothing
    }

    public void storeFloatField(int fieldNumber, float value)
    {
        // Do nothing
    }

    public void storeIntField(int fieldNumber, int value)
    {
        // Do nothing
    }

    public void storeLongField(int fieldNumber, long value)
    {
        // Do nothing
    }

    public void storeShortField(int fieldNumber, short value)
    {
        // Do nothing
    }

    public void storeStringField(int fieldNumber, String value)
    {
        // Do nothing
    }
}