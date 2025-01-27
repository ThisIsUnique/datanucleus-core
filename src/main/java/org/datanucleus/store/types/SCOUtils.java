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
2007 Xuan Baldauf - Make error message "023011" a little bit more verbose
    ...
**********************************************************************/
package org.datanucleus.store.types;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlanState;
import org.datanucleus.PropertyNames;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.MetaData;
import org.datanucleus.metadata.MetaDataManager;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.types.scostore.CollectionStore;
import org.datanucleus.store.types.scostore.MapStore;
import org.datanucleus.store.types.scostore.SetStore;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Collection of utilities for second class wrappers and objects.
 */
public class SCOUtils
{
    /**
     * Method to unwrap a SCO field/property (if it is wrapped currently). If the member value is not a SCO will just return the value.
     * @param ownerSM StateManager of the owner
     * @param memberNumber The member number in the owner
     * @param sco The SCO value for the member
     * @return The unwrapped member value
     * TODO Move to TypeManager
     */
    public static Object unwrapSCOField(DNStateManager ownerSM, int memberNumber, SCO sco)
    {
        if (sco == null)
        {
            return null;
        }

        Object unwrappedValue = sco.getValue();
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            AbstractMemberMetaData mmd = ownerSM.getClassMetaData().getMetaDataForManagedMemberAtAbsolutePosition(memberNumber);
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("026030", IdentityUtils.getPersistableIdentityForId(ownerSM.getInternalObjectId()), mmd.getName()));
        }
        ownerSM.replaceField(memberNumber, unwrappedValue);
        return unwrappedValue;
    }

    /**
     * Method to create a new SCO wrapper for the specified field/property. If the member value is a SCO already will just return the value.
     * @param ownerSM StateManager of the owner
     * @param memberNumber The member number in the owner
     * @param value The value to initialise the wrapper with (if any)
     * @param replaceFieldIfChanged Whether to replace the member in the object if wrapping the value
     * @return The wrapper (or original value if not wrappable)
     * TODO Move to TypeManager
     */
    public static Object wrapSCOField(DNStateManager ownerSM, int memberNumber, Object value, boolean replaceFieldIfChanged)
    {
        if (value == null || !ownerSM.getClassMetaData().getSCOMutableMemberFlags()[memberNumber])
        {
            // We don't wrap null objects currently
            return value;
        }

        if (!(value instanceof SCO) || ownerSM.getObject() != ((SCO)value).getOwner())
        {
            // Not a SCO wrapper, or is a SCO wrapper but not owned by this object
            AbstractMemberMetaData mmd = ownerSM.getClassMetaData().getMetaDataForManagedMemberAtAbsolutePosition(memberNumber);
            if (replaceFieldIfChanged)
            {
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("026029",
                        ownerSM.getExecutionContext() != null ? IdentityUtils.getPersistableIdentityForId(ownerSM.getInternalObjectId()) : ownerSM.getInternalObjectId(), 
                        mmd.getName()));
                }
            }
            return ownerSM.getExecutionContext().getTypeManager().createSCOInstance(ownerSM, mmd, value.getClass(), value, replaceFieldIfChanged);
        }
        return value;
    }

    /**
     * Utility to generate a message representing the SCO container wrapper and its capabilities.
     * @param ownerSM StateManager for the owner
     * @param fieldName Field with the container
     * @param cont The SCOContainer
     * @param useCache Whether to use caching of values in the container
     * @param allowNulls Whether to allow nulls
     * @param lazyLoading Whether to use lazy loading in the wrapper
     * @return The String
     */
    public static String getContainerInfoMessage(DNStateManager ownerSM, String fieldName, SCOContainer cont, boolean useCache, boolean allowNulls, boolean lazyLoading)
    {
        String msg = Localiser.msg("023004", ownerSM.getObjectAsPrintable(), fieldName, cont.getClass().getName(),
            "[cache-values=" + useCache + ", lazy-loading=" + lazyLoading + ", allow-nulls=" + allowNulls + "]");
        return msg;
    }

    /**
     * Convenience method to generate a message containing the options of this SCO wrapper.
     * @param useCache Whether to cache the value in the wrapper (and not go to the datastore)
     * @param queued Whether it supports queueing of updates
     * @param allowNulls Whether it allows null entries
     * @param lazyLoading Whether it is lazy loaded
     * @return the message
     */
    public static String getSCOWrapperOptionsMessage(boolean useCache, boolean queued, boolean allowNulls, boolean lazyLoading)
    {
        StringBuilder str = new StringBuilder();
        if (useCache)
        {
            str.append("cached");
        }
        if (lazyLoading)
        {
            if (str.length() > 0)
            {
                str.append(",");
            }
            str.append("lazy-loaded");
        }
        if (queued)
        {
            if (str.length() > 0)
            {
                str.append(",");
            }
            str.append("queued");
        }
        if (allowNulls)
        {
            if (str.length() > 0)
            {
                str.append(",");
            }
            str.append("allowNulls");
        }
        return str.toString();
    }

    /**
     * Utility to return whether or not to allow nulls in the container for the specified field.
     * @param defaultValue Default value for the container
     * @param mmd MetaData for the field/property
     * @return Whether to allow nulls
     */
    public static boolean allowNullsInContainer(boolean defaultValue, AbstractMemberMetaData mmd)
    {
        if (mmd.getContainer() == null)
        {
            return defaultValue;
        }
        else if (Boolean.TRUE.equals(mmd.getContainer().allowNulls()))
        {
            return true;
        }
        else if (Boolean.FALSE.equals(mmd.getContainer().allowNulls()))
        {
            return false;
        }
        return defaultValue;
    }

    /**
     * Utility to return whether or not to use the container cache for the collection/map for the passed StateManager SCO.
     * @param ownerSM StateManager for the SCO field
     * @param mmd Metadata for the member that we are considering
     * @return Whether to use the cache.
     */
    public static boolean useContainerCache(DNStateManager ownerSM, AbstractMemberMetaData mmd)
    {
        if (ownerSM == null)
        {
            return false;
        }

        // Check whether we should cache collections based on PMF/PM
        boolean useCache = ownerSM.getExecutionContext().getNucleusContext().getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_CACHE_COLLECTIONS);
        if (ownerSM.getExecutionContext().getBooleanProperty(PropertyNames.PROPERTY_CACHE_COLLECTIONS) != null)
        {
            useCache = ownerSM.getExecutionContext().getBooleanProperty(PropertyNames.PROPERTY_CACHE_COLLECTIONS);
        }

        if (mmd.getOrderMetaData() != null && !mmd.getOrderMetaData().isIndexedList())
        {
            // "Ordered Lists" have to use caching since most List operations are impossible without indexing
            useCache = true;
        }
        else if (mmd.getContainer() != null && mmd.getContainer().hasExtension("cache"))
        {
            // User has marked the field caching policy
            useCache = Boolean.parseBoolean(mmd.getContainer().getValueForExtension("cache"));
        }

        return useCache;
    }

    /**
     * Accessor for whether the use lazy loading when caching the collection.
     * @param ownerSM StateManager of the owning object
     * @param mmd Meta-data of the collection/map field
     * @return Whether to use lazy loading when caching the collection
     */
    public static boolean useCachedLazyLoading(DNStateManager ownerSM, AbstractMemberMetaData mmd)
    {
        if (ownerSM == null)
        {
            return false;
        }

        boolean lazy = false;

        AbstractClassMetaData cmd = ownerSM.getClassMetaData();
        Boolean lazyCollections = ownerSM.getExecutionContext().getNucleusContext().getConfiguration().getBooleanObjectProperty(PropertyNames.PROPERTY_CACHE_COLLECTIONS_LAZY);
        if (lazyCollections != null)
        {
            // Global setting for PMF
            lazy = lazyCollections.booleanValue();
        }
        else if (mmd.getContainer() != null && mmd.getContainer().hasExtension("cache-lazy-loading"))
        {
            // Check if this container has a MetaData value defined
            lazy = Boolean.parseBoolean(mmd.getContainer().getValueForExtension("cache-lazy-loading"));
        }
        else
        {
            // Check if this SCO is in the current FetchPlan
            boolean inFP = false;
            int[] fpFields = ownerSM.getExecutionContext().getFetchPlan().getFetchPlanForClass(cmd).getMemberNumbers();
            int fieldNo = mmd.getAbsoluteFieldNumber();
            if (fpFields != null && fpFields.length > 0)
            {
                for (int i = 0; i < fpFields.length; i++)
                {
                    if (fpFields[i] == fieldNo)
                    {
                        inFP = true;
                        break;
                    }
                }
            }
            // Default to lazy loading when not in FetchPlan, and non-lazy when in FetchPlan
            lazy = !inFP;
        }

        return lazy;
    }

    /**
     * Convenience method to return if a collection field has elements without their own identity. Checks if
     * the elements are embedded in a join table, or in the main table, or serialised.
     * @param mmd MetaData for the field
     * @return Whether the elements have their own identity or not
     */
    public static boolean collectionHasElementsWithoutIdentity(AbstractMemberMetaData mmd)
    {
        boolean elementsWithoutIdentity = false;
        if (mmd.isSerialized())
        {
            // Elements serialised into main table
            elementsWithoutIdentity = true;
        }
        else if (mmd.getElementMetaData() != null && mmd.getElementMetaData().getEmbeddedMetaData() != null && mmd.getJoinMetaData() != null)
        {
            // Elements embedded in join table using embedded mapping
            elementsWithoutIdentity = true;
        }
        else if (mmd.getCollection() != null && mmd.getCollection().isEmbeddedElement())
        {
            // Elements are embedded (either serialised, or embedded in join table)
            elementsWithoutIdentity = true;
        }

        return elementsWithoutIdentity;
    }

    /**
     * Convenience method to return if a map field has keys without their own identity. Checks if the keys are
     * embedded in a join table, or in the main table, or serialised.
     * @param fmd MetaData for the field
     * @return Whether the keys have their own identity or not
     */
    public static boolean mapHasKeysWithoutIdentity(AbstractMemberMetaData fmd)
    {
        boolean keysWithoutIdentity = false;
        if (fmd.isSerialized())
        {
            // Keys (and values) serialised into main table
            keysWithoutIdentity = true;
        }
        else if (fmd.getKeyMetaData() != null && fmd.getKeyMetaData().getEmbeddedMetaData() != null && fmd.getJoinMetaData() != null)
        {
            // Keys embedded in join table using embedded mapping
            keysWithoutIdentity = true;
        }
        else if (fmd.getMap() != null && fmd.getMap().isEmbeddedKey())
        {
            // Keys are embedded (either serialised, or embedded in join table)
            keysWithoutIdentity = true;
        }

        return keysWithoutIdentity;
    }

    /**
     * Convenience method to return if a map field has values without their own identity. Checks if the values
     * are embedded in a join table, or in the main table, or serialised.
     * @param fmd MetaData for the field
     * @return Whether the values have their own identity or not
     */
    public static boolean mapHasValuesWithoutIdentity(AbstractMemberMetaData fmd)
    {
        boolean valuesWithoutIdentity = false;
        if (fmd.isSerialized())
        {
            // Values (and keys) serialised into main table
            valuesWithoutIdentity = true;
        }
        else if (fmd.getValueMetaData() != null && fmd.getValueMetaData().getEmbeddedMetaData() != null && fmd.getJoinMetaData() != null)
        {
            // Values embedded in join table using embedded mapping
            valuesWithoutIdentity = true;
        }
        else if (fmd.getMap() != null && fmd.getMap().isEmbeddedValue())
        {
            // Values are embedded (either serialised, or embedded in join table)
            valuesWithoutIdentity = true;
        }

        return valuesWithoutIdentity;
    }

    /**
     * Convenience method to return if a collection field has the elements serialised into the table of the
     * field as a single BLOB. This is really for use within an RDBMS context.
     * @param fmd MetaData for the field
     * @return Whether the elements are serialised (either explicitly or implicitly)
     */
    public static boolean collectionHasSerialisedElements(AbstractMemberMetaData fmd)
    {
        boolean serialised = fmd.isSerialized();
        if (fmd.getCollection() != null && fmd.getCollection().isEmbeddedElement() && fmd.getJoinMetaData() == null)
        {
            // Elements are embedded but no join table so we serialise
            serialised = true;
        }

        return serialised;
    }

    /**
     * Convenience method to return if an array field has the elements stored into the table of the field as a
     * single (BLOB) column.
     * @param fmd MetaData for the field
     * @param mmgr MetaData manager
     * @return Whether the elements are stored in a single column
     */
    public static boolean arrayIsStoredInSingleColumn(AbstractMemberMetaData fmd, MetaDataManager mmgr)
    {
        boolean singleColumn = fmd.isSerialized();
        if (!singleColumn && fmd.getArray() != null && fmd.getJoinMetaData() == null)
        {
            if (fmd.getArray().isEmbeddedElement())
            {
                // Elements are embedded but no join table so we store in a single column
                singleColumn = true;
            }

            Class elementClass = fmd.getType().getComponentType();
            ApiAdapter api = mmgr.getApiAdapter();
            if (!elementClass.isInterface() && !api.isPersistable(elementClass))
            {
                // Array of non-PC with no join table so store in single column of main table
                singleColumn = true;
            }
        }

        return singleColumn;
    }

    /**
     * Convenience method to return if a map field has the keys/values serialised. This is really for use
     * within an RDBMS context.
     * @param fmd MetaData for the field
     * @return Whether the keys and values are serialised (either explicitly or implicitly)
     */
    public static boolean mapHasSerialisedKeysAndValues(AbstractMemberMetaData fmd)
    {
        boolean inverseKeyField = false;
        if (fmd.getKeyMetaData() != null && fmd.getKeyMetaData().getMappedBy() != null)
        {
            inverseKeyField = true;
        }
        boolean inverseValueField = false;
        if (fmd.getValueMetaData() != null && fmd.getValueMetaData().getMappedBy() != null)
        {
            inverseValueField = true;
        }

        boolean serialised = fmd.isSerialized();
        if (fmd.getMap() != null && fmd.getJoinMetaData() == null && (fmd.getMap().isEmbeddedKey() && fmd.getMap().isEmbeddedValue()) && !inverseKeyField && !inverseValueField)
        {
            // Keys AND values are embedded but no join table so we serialise the whole map
            // Note that we explicitly excluded the 1-N Map with the key stored in the value
            serialised = true;
        }

        return serialised;
    }

    /**
     * Convenience method for use by Collection/Set/HashSet attachCopy methods to add any new elements (added
     * whilst detached) to the collection.
     * @param ownerSM StateManager for the owner
     * @param scoColl The current (attached) SCO collection
     * @param detachedElements The collection of (detached) elements that we're merging
     * @param elementsWithoutId Whether the elements have no identity
     * @return If the Collection was updated
     */
    public static boolean attachCopyElements(DNStateManager ownerSM, Collection scoColl, Collection detachedElements, boolean elementsWithoutId)
    {
        boolean updated = false;

        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();

        // Delete any elements that are no longer in the collection
        Iterator scoCollIter = scoColl.iterator();
        while (scoCollIter.hasNext())
        {
            Object currentElem = scoCollIter.next();
            Object currentElemId = api.getIdForObject(currentElem);
            Iterator desiredIter = detachedElements.iterator();
            boolean contained = false;
            if (elementsWithoutId)
            {
                contained = detachedElements.contains(currentElem);
            }
            else
            {
                while (desiredIter.hasNext())
                {
                    Object desiredElem = desiredIter.next();
                    if (currentElemId != null)
                    {
                        if (currentElemId.equals(api.getIdForObject(desiredElem)))
                        {
                            // Identity equal so same
                            contained = true;
                            break;
                        }
                    }
                    else
                    {
                        if (currentElem == desiredElem)
                        {
                            // Same object
                            contained = true;
                            break;
                        }
                    }
                }
            }
            if (!contained)
            {
                // No longer present so remove it
                scoCollIter.remove();
                updated = true;
            }
        }

        Iterator detachedElementsIter = detachedElements.iterator();
        while (detachedElementsIter.hasNext())
        {
            Object detachedElement = detachedElementsIter.next();
            if (elementsWithoutId)
            {
                // Non-PC element
                if (!scoColl.contains(detachedElement))
                {
                    scoColl.add(detachedElement);
                    updated = true;
                }
            }
            else
            {
                // PC element, so compare by id (if present)
                Object detachedElemId = api.getIdForObject(detachedElement);
                scoCollIter = scoColl.iterator();
                boolean contained = false;
                while (scoCollIter.hasNext())
                {
                    Object scoCollElem = scoCollIter.next();
                    Object scoCollElemId = api.getIdForObject(scoCollElem);
                    if (scoCollElemId != null && scoCollElemId.equals(detachedElemId))
                    {
                        contained = true;
                        break;
                    }
                }

                if (!contained)
                {
                    scoColl.add(detachedElement);
                    updated = true;
                }
                else
                {
                    ownerSM.getExecutionContext().attachObjectCopy(ownerSM, detachedElement, false);
                }
            }
        }

        return updated;
    }

    /**
     * Method to return an attached copy of the passed (detached) value. The returned attached copy is a SCO
     * wrapper. Goes through the existing elements in the store for this owner field and removes ones no
     * longer present, and adds new elements. All elements in the (detached) value are attached.
     * @param ownerSM StateManager for the owning object with the collection
     * @param detachedElements The detached elements in the collection
     * @param attached Collection to add the attached copies to
     * @param elementsWithoutIdentity Whether the elements have their own identity
     */
    public static void attachCopyForCollection(DNStateManager ownerSM, Object[] detachedElements, Collection attached, boolean elementsWithoutIdentity)
    {
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        for (int i = 0; i < detachedElements.length; i++)
        {
            if (api.isPersistable(detachedElements[i]) && api.isDetachable(detachedElements[i]))
            {
                attached.add(ownerSM.getExecutionContext().attachObjectCopy(ownerSM, detachedElements[i], elementsWithoutIdentity));
            }
            else
            {
                attached.add(detachedElements[i]);
            }
        }
    }

    /**
     * Method to return an attached copy of the passed (detached) value. The returned attached copy is a SCO
     * wrapper. Goes through the existing elements in the store for this owner field and removes ones no
     * longer present, and adds new elements. All elements in the (detached) value are attached.
     * @param ownerSM StateManager for the owning object with the map
     * @param detachedEntries The detached entries in the map
     * @param attached Map to add the attached copies to
     * @param keysWithoutIdentity Whether the keys have their own identity
     * @param valuesWithoutIdentity Whether the values have their own identity
     */
    public static void attachCopyForMap(DNStateManager ownerSM, Set detachedEntries, Map attached, boolean keysWithoutIdentity, boolean valuesWithoutIdentity)
    {
        Iterator iter = detachedEntries.iterator();
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        while (iter.hasNext())
        {
            Map.Entry entry = (Map.Entry) iter.next();
            Object val = entry.getValue();
            Object key = entry.getKey();
            if (api.isPersistable(val) && api.isDetachable(val))
            {
                val = ownerSM.getExecutionContext().attachObjectCopy(ownerSM, val, valuesWithoutIdentity);
            }
            if (api.isPersistable(key) && api.isDetachable(key))
            {
                key = ownerSM.getExecutionContext().attachObjectCopy(ownerSM, key, keysWithoutIdentity);
            }
            attached.put(key, val);
        }
    }

    /**
     * Convenience method to update a Collection to match the provided elements.
     * @param api Api adapter
     * @param coll The collection to update
     * @param elements The new collection of elements that we need to match
     * @return Whether the collection was updated
     */
    public static boolean updateCollectionWithCollection(ApiAdapter api, java.util.Collection coll, java.util.Collection elements)
    {
        boolean updated = false;

        java.util.Collection unwrapped = coll;
        if (coll instanceof SCO)
        {
            unwrapped = (java.util.Collection)((SCO)coll).getValue();
        }

        java.util.Collection unwrappedCopy = new java.util.HashSet(unwrapped);

        // Check for new elements not previously present
        for (Object elem : elements)
        {
            if (api.isPersistable(elem) && !api.isPersistent(elem))
            {
                // Not present so add it
                coll.add(elem);
                updated = true;
            }
            else if (!unwrapped.contains(elem))
            {
                coll.add(elem);
                updated = true;
            }
        }

        // Check for elements that are no longer present
        for (Object elem : unwrappedCopy)
        {
            if (!elements.contains(elem))
            {
                coll.remove(elem);
                updated = true;
            }
        }

        return updated;
    }

    /**
     * Convenience method for use by List attachCopy methods to update the passed (attached) list using the (attached) list elements passed.
     * @param list The current (attached) list
     * @param elements The list of (attached) elements needed.
     * @return If the List was updated
     */
    public static boolean updateListWithListElements(List list, List elements)
    {
        boolean updated = false;

        // This method needs to take the existing list and generate a list
        // of add/remove/set/clear operations that change the list to the passed
        // elements in as efficient a way as possible. The simplest is
        // clear() then addAll()!, but if there are many objects and very little
        // has changed this would be very inefficient.
        // What we do currently is remove all elements no longer present, and then
        // add any missing elements, correcting the ordering. This can be non-optimal
        // in some situations.
        // TODO Optimise the process
        // Delete any elements that are no longer in the list
        java.util.ArrayList newCopy = new java.util.ArrayList(elements);
        Iterator attachedIter = list.iterator();
        while (attachedIter.hasNext())
        {
            Object attachedElement = attachedIter.next();
            if (!newCopy.remove(attachedElement))
            {
                // No longer present, so remove it
                attachedIter.remove();
                updated = true;
            }
        }

        // Add any new elements that have been added
        java.util.ArrayList oldCopy = new java.util.ArrayList(list);
        Iterator elementsIter = elements.iterator();
        while (elementsIter.hasNext())
        {
            Object element = elementsIter.next();
            if (!oldCopy.remove(element)) // Why remove it if wanting to check if present?
            {
                // Now present, so add it
                list.add(element);
                updated = true;
            }
        }

        // Update position of elements in the list to match the new order
        elementsIter = elements.iterator();
        int position = 0;
        while (elementsIter.hasNext())
        {
            Object element = elementsIter.next();
            Object currentElement = list.get(position);
            boolean updatePosition = false;
            if ((element == null && currentElement != null) || (element != null && currentElement == null))
            {
                // Cater for null elements in the list
                updatePosition = true;
            }
            else if (element != null && currentElement != null && !currentElement.equals(element))
            {
                updatePosition = true;
            }

            if (updatePosition)
            {
                // Update the position, taking care not to have dependent-field deletes taking place
                ((SCOList) list).set(position, element, false);
                updated = true;
            }

            position++;
        }

        return updated;
    }

    /**
     * Convenience method for use by Map attachCopy methods to update the passed (attached) map using the (attached) map keys/values passed.
     * @param api Api adapter
     * @param map The current (attached) map
     * @param keysValues The keys/values required
     * @return If the map was updated
     */
    public static boolean updateMapWithMapKeysValues(ApiAdapter api, Map map, Map keysValues)
    {
        boolean updated = false;

        // Copy the original map, so we know which ones to check for removal later
        java.util.Map copy = new java.util.HashMap(map);

        // Add any new keys/values and update any changed values
        Iterator keysIter = keysValues.entrySet().iterator();
        while (keysIter.hasNext())
        {
            Map.Entry entry = (Map.Entry) keysIter.next();
            Object key = entry.getKey();
            if (api.isPersistable(key) && !api.isPersistent(key))
            {
                // Not present so add it
                map.put(key, keysValues.get(key));
                updated = true;
            }
            else if (!map.containsKey(key))
            {
                // Not present so add it
                map.put(key, keysValues.get(key));
                updated = true;
            }
            else
            {
                // Update any values
                Object value = entry.getValue();
                Object oldValue = map.get(key);
                if (api.isPersistable(value) && !api.isPersistent(value))
                {
                    // New persistable value
                    map.put(key, value);
                }
                else if (api.isPersistable(value) && api.getIdForObject(value) != api.getIdForObject(oldValue))
                {
                    // In case they have changed the PC for this key (different id)
                    map.put(key, value);
                }
                else
                {
                    if ((oldValue == null && value != null) || (oldValue != null && !oldValue.equals(value)))
                    {
                        map.put(key, value);
                    }
                }
            }
        }

        // Delete any keys that are no longer in the Map
        Iterator<Map.Entry> attachedIter = copy.entrySet().iterator();
        while (attachedIter.hasNext())
        {
            Map.Entry entry = attachedIter.next();
            Object key = entry.getKey();
            if (!keysValues.containsKey(key))
            {
                map.remove(key);
                updated = true;
            }
        }

        return updated;
    }

    /**
     * Convenience method to populate the passed delegate Map with the keys/values from the associated Store.
     * <P>
     * The issue here is that we need to load the keys and values in as few calls as possible. The method
     * employed here reads in the keys (if persistable), then the values (if persistable), and then the
     * "entries" (ids of keys and values) so we can associate the keys to the values.
     * @param delegate The delegate
     * @param store The Store
     * @param ownerSM StateManager of the owner of the map.
     * @param <K> Type of the map key
     * @param <V> Type of the map value
     */
    public static <K, V> void populateMapDelegateWithStoreData(Map<K, V> delegate, MapStore<K, V> store, DNStateManager ownerSM)
    {
        // If we have persistable keys then load them. The keys query will pull in the key fetch plan so this instantiates them in the cache
        java.util.Set<K> keys = new java.util.HashSet<>();
        if (!store.keysAreEmbedded() && !store.keysAreSerialised())
        {
            // Retrieve the persistable keys
            SetStore<K> keystore = store.keySetStore();
            Iterator<K> keyIter = keystore.iterator(ownerSM);
            while (keyIter.hasNext())
            {
                keys.add(keyIter.next());
            }
        }

        // If we have persistable values then load them. The values query will pull in the value fetch plan so this instantiates them in the cache
        java.util.List<V> values = new java.util.ArrayList<>();
        if (!store.valuesAreEmbedded() && !store.valuesAreSerialised())
        {
            // Retrieve the persistable values
            CollectionStore<V> valuestore = store.valueCollectionStore();
            Iterator<V> valueIter = valuestore.iterator(ownerSM);
            while (valueIter.hasNext())
            {
                values.add(valueIter.next());
            }
        }

        // Retrieve the entries (key-value pairs so we can associate them)
        // TODO Ultimately would like to just call this, but the entry query can omit the inheritance level of a key or value
        // Likely the best thing to do is check for inheritance in key/value persistable types and if none then do in single entry call
        SetStore<Map.Entry<K, V>> entries = store.entrySetStore();
        Iterator<Map.Entry<K, V>> entryIter = entries.iterator(ownerSM);
        while (entryIter.hasNext())
        {
            Map.Entry<K, V> entry = entryIter.next();
            K key = entry.getKey();
            V value = entry.getValue();
            delegate.put(key, value);
        }

        if (!store.keysAreEmbedded() && !store.keysAreSerialised() && delegate.size() != keys.size())
        {
            // With Derby 10.x we can get instances where the values query returns no values yet entries is not empty TODO Maybe make this throw an exception
            NucleusLogger.DATASTORE_RETRIEVE.warn("The number of Map key objects (" + keys.size() + ")" + " was different to the number of entries (" + delegate.size() + 
                ")." + " Likely there is a bug in your datastore");
        }

        if (!store.valuesAreEmbedded() && !store.valuesAreSerialised() && delegate.size() != values.size())
        {
            // With Derby 10.x we can get instances where the values query returns no values yet entries is not empty TODO Maybe make this throw an exception
            NucleusLogger.DATASTORE_RETRIEVE.warn("The number of Map value objects (" + values.size() + ")" + " was different to the number of entries (" + delegate.size() + 
                ")." + " Likely there is a bug in your datastore, or you have null values?");
        }

        keys.clear();
        values.clear();
    }

    /**
     * Returns <i>true</i> if this collection contains the specified element. More formally, returns
     * <i>true</i> if and only if this collection contains at least one element <i>it</i> such that
     * <i>(o==null ? it==null : o.equals(it))</i>.
     * <p>
     * This implementation iterates over the elements in the collection, checking each element in turn for
     * equality with the specified element.
     * @param backingStore the Store
     * @param sm StateManager
     * @return <i>true</i> if this collection contains the specified element.
     */
    public static Object[] toArray(CollectionStore backingStore, DNStateManager sm)
    {
        Object[] result = new Object[backingStore.size(sm)];
        Iterator it = backingStore.iterator(sm);
        for (int i = 0; it.hasNext(); i++)
        {
            result[i] = it.next();
        }
        return result;
    }

    /**
     * Returns an array containing all of the elements in this collection;
     * @param backingStore the Store
     * @param sm StateManager
     * @param a the array into which the elements of the collection are to be stored, if it is big enough;
     * otherwise, a new array of the same runtime type is allocated for this purpose.
     * @return an array containing the elements of the collection.
     * @throws NullPointerException if the specified array is <i>null</i>.
     * @throws ArrayStoreException if the runtime type of the specified array is not a supertype of the
     * runtime type of every element in this collection.
     */
    public static Object[] toArray(CollectionStore backingStore, DNStateManager sm, Object a[])
    {
        int size = backingStore.size(sm);
        if (a.length < size)
        {
            a = (Object[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        }
        Iterator it = backingStore.iterator(sm);
        for (int i = 0; i < size; i++)
        {
            a[i] = it.next();
        }

        if (a.length > size)
        {
            a[size] = null;
        }

        return a;
    }

    /**
     * Convenience method for creating a Comparator using extension metadata tags for the specified field.
     * Uses the extension key "comparator-name".
     * @param mmd The field that needs the comparator
     * @param clr ClassLoader resolver
     * @return The Comparator
     */
    public static Comparator getComparator(AbstractMemberMetaData mmd, ClassLoaderResolver clr)
    {
        Comparator comparator = null;
        String comparatorName = null;
        // TODO Support class in same package as the fields class
        if (mmd.hasMap())
        {
            // Specified under <field> or <map>
            if (mmd.hasExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME))
            {
                comparatorName = mmd.getValueForExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME);
            }
            else if (mmd.getMap().hasExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME))
            {
                comparatorName = mmd.getMap().getValueForExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME);
            }
        }
        else if (mmd.hasCollection())
        {
            // Specified under <field> or <collection>
            if (mmd.hasExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME))
            {
                comparatorName = mmd.getValueForExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME);
            }
            else if (mmd.getCollection().hasExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME))
            {
                comparatorName = mmd.getCollection().getValueForExtension(MetaData.EXTENSION_MEMBER_COMPARATOR_NAME);
            }
        }

        if (comparatorName != null)
        {
            Class comparatorCls = null;
            try
            {
                comparatorCls = clr.classForName(comparatorName);
                comparator = (Comparator) ClassUtils.newInstance(comparatorCls, null, null);
            }
            catch (NucleusException jpe)
            {
                NucleusLogger.PERSISTENCE.warn(Localiser.msg("023012", mmd.getFullFieldName(), comparatorName));
            }
        }
        return comparator;
    }

    /**
     * Convenience method to detach (recursively) all elements for a collection field. All elements that are
     * persistable will be detached.
     * @param ownerSM StateManager for the owning object with the collection
     * @param elements The elements in the collection
     * @param state FetchPlan state
     */
    public static void detachForCollection(DNStateManager ownerSM, Object[] elements, FetchPlanState state)
    {
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        for (int i = 0; i < elements.length; i++)
        {
            if (api.isPersistable(elements[i]))
            {
                ownerSM.getExecutionContext().detachObject(state, elements[i]);
            }
        }
    }

    /**
     * Convenience method to detach copies (recursively) of all elements for a collection field. All elements
     * that are persistable will be detached.
     * @param ownerSM StateManager for the owning object with the collection
     * @param elements The elements in the collection
     * @param state FetchPlan state
     * @param detached Collection to add the detached copies to
     */
    public static void detachCopyForCollection(DNStateManager ownerSM, Object[] elements, FetchPlanState state, Collection detached)
    {
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        for (int i = 0; i < elements.length; i++)
        {
            if (elements[i] == null)
            {
                detached.add(null);
            }
            else
            {
                Object object = elements[i];
                if (api.isPersistable(object))
                {
                    detached.add(ownerSM.getExecutionContext().detachObjectCopy(state, object));
                }
                else
                {
                    detached.add(object);
                }
            }
        }
    }

    /**
     * Convenience method to attach (recursively) all elements for a collection field. All elements that are
     * persistable and not yet having an attached object will be attached.
     * @param ownerSM StateManager for the owning object with the collection
     * @param elements The elements to process
     * @param elementsWithoutIdentity Whether the elements have their own identity
     */
    public static void attachForCollection(DNStateManager ownerSM, Object[] elements, boolean elementsWithoutIdentity)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        ApiAdapter api = ec.getApiAdapter();
        for (int i = 0; i < elements.length; i++)
        {
            if (api.isPersistable(elements[i]))
            {
                Object attached = ec.getAttachedObjectForId(api.getIdForObject(elements[i]));
                if (attached == null)
                {
                    // Not yet attached so attach
                    ec.attachObject(ownerSM, elements[i], elementsWithoutIdentity);
                }
            }
        }
    }

    /**
     * Convenience method to detach (recursively) all elements for a map field. All elements that are
     * persistable will be detached.
     * @param ownerSM StateManager for the owning object with the map
     * @param entries The entries in the map
     * @param state FetchPlan state
     */
    public static void detachForMap(DNStateManager ownerSM, Set entries, FetchPlanState state)
    {
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        for (Iterator it = entries.iterator(); it.hasNext();)
        {
            Map.Entry entry = (Map.Entry) it.next();
            Object val = entry.getValue();
            Object key = entry.getKey();
            if (api.isPersistable(key))
            {
                ownerSM.getExecutionContext().detachObject(state, key);
            }
            if (api.isPersistable(val))
            {
                ownerSM.getExecutionContext().detachObject(state, val);
            }
        }
    }

    /**
     * Convenience method to detach copies (recursively) of all elements for a map field. All elements that
     * are persistable will be detached.
     * @param ownerSM StateManager for the owning object with the map
     * @param entries The entries in the map
     * @param state FetchPlan state
     * @param detached Map to add the detached copies to
     */
    public static void detachCopyForMap(DNStateManager ownerSM, Set entries, FetchPlanState state, Map detached)
    {
        ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
        for (Iterator it = entries.iterator(); it.hasNext();)
        {
            Map.Entry entry = (Map.Entry) it.next();
            Object val = entry.getValue();
            Object key = entry.getKey();
            if (api.isPersistable(val))
            {
                val = ownerSM.getExecutionContext().detachObjectCopy(state, val);
            }
            if (api.isPersistable(key))
            {
                key = ownerSM.getExecutionContext().detachObjectCopy(state, key);
            }
            detached.put(key, val);
        }
    }

    /**
     * Convenience method to attach (recursively) all keys/values for a map field. All keys/values that are
     * persistable and don't already have an attached object will be attached.
     * @param ownerSM StateManager for the owning object with the map
     * @param entries The entries in the map to process
     * @param keysWithoutIdentity Whether the keys have their own identity
     * @param valuesWithoutIdentity Whether the values have their own identity
     */
    public static void attachForMap(DNStateManager ownerSM, Set entries, boolean keysWithoutIdentity, boolean valuesWithoutIdentity)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        ApiAdapter api = ec.getApiAdapter();
        for (Iterator it = entries.iterator(); it.hasNext();)
        {
            Map.Entry entry = (Map.Entry) it.next();
            Object val = entry.getValue();
            Object key = entry.getKey();
            if (api.isPersistable(key))
            {
                Object attached = ec.getAttachedObjectForId(api.getIdForObject(key));
                if (attached == null)
                {
                    // Not yet attached so attach
                    ownerSM.getExecutionContext().attachObject(ownerSM, key, keysWithoutIdentity);
                }
            }
            if (api.isPersistable(val))
            {
                Object attached = ec.getAttachedObjectForId(api.getIdForObject(val));
                if (attached == null)
                {
                    // Not yet attached so attach
                    ownerSM.getExecutionContext().attachObject(ownerSM, val, valuesWithoutIdentity);
                }
            }
        }
    }

    /**
     * Method to check if an object to be stored in a SCO container is already persistent, or is managed by a
     * different ExecutionContext. If not persistent, this call will persist it. 
     * If not yet flushed to the datastore this call will flush it.
     * @param ec ExecutionContext
     * @param object The object
     * @param fieldValues Values for any fields when persisting (if the object needs persisting)
     * @return Whether the object was persisted during this call
     */
    public static boolean validateObjectForWriting(ExecutionContext ec, Object object, FieldValues fieldValues)
    {
        boolean persisted = false;
        ApiAdapter api = ec.getApiAdapter();
        if (api.isPersistable(object))
        {
            ExecutionContext objectEC = api.getExecutionContext(object);
            if (objectEC != null && ec != objectEC)
            {
                throw new NucleusUserException(Localiser.msg("023009", StringUtils.toJVMIDString(object)), api.getIdForObject(object));
            }
            else if (!api.isPersistent(object))
            {
                // Not persistent, so either is detached, or needs persisting for first time
                boolean exists = false;
                if (api.isDetached(object))
                {
                    if (ec.getBooleanProperty(PropertyNames.PROPERTY_ATTACH_SAME_DATASTORE))
                    {
                        // Assume that it is detached from this datastore
                        exists = true;
                    }
                    else
                    {
                        // Check if the (attached) object exists in this datastore
                        try
                        {
                            Object obj = ec.findObject(api.getIdForObject(object), true, false, object.getClass().getName());
                            if (obj != null)
                            {
                                // PM.getObjectById creates a dummy object to represent this object and
                                // automatically
                                // enlists it in the txn. Evict it to avoid issues with reachability
                                DNStateManager objSM = ec.findStateManager(obj);
                                if (objSM != null)
                                {
                                    ec.evictFromTransaction(objSM);
                                }
                            }
                            exists = true;
                        }
                        catch (NucleusObjectNotFoundException onfe)
                        {
                            exists = false;
                        }
                    }
                }
                if (!exists)
                {
                    // Persist the object
                    ec.persistObjectInternal(object, fieldValues, DNStateManager.PC);
                    persisted = true;
                }
            }
            else
            {
                // Persistent state, but is it flushed to the datastore?
                DNStateManager objectSM = ec.findStateManager(object);
                if (objectSM.isWaitingToBeFlushedToDatastore())
                {
                    // Newly persistent but still not flushed (e.g in optimistic txn)
                    // Process any fieldValues
                    if (fieldValues != null)
                    {
                        objectSM.loadFieldValues(fieldValues);
                    }

                    // Now flush it
                    objectSM.flush();
                    persisted = true; // Mark as being persisted since is now in the datastore
                }
            }
        }
        return persisted;
    }

    /**
     * Return whether the supplied type (collection) is list based.
     * @param type Type to check
     * @return Whether it needs list ordering
     */
    public static boolean isListBased(Class type)
    {
        if (type == null)
        {
            return false;
        }
        else if (java.util.List.class.isAssignableFrom(type))
        {
            return true;
        }
        else if (java.util.Queue.class.isAssignableFrom(type))
        {
            // Queue needs ordering
            return true;
        }
        return false;
    }

    /**
     * Method to return the type to instantiate a container as. Returns the declared type unless it is not a
     * concrete type, in which case returns ArrayList, HashSet, or HashMap.
     * @param declaredType The declared type
     * @param ordered Hint whether it needs ordering or not (null implies not)
     * @return The type to instantiate as
     */
    public static Class getContainerInstanceType(Class declaredType, Boolean ordered)
    {
        if (declaredType.isInterface())
        {
            // Instantiate as most appropriate concrete implementation
            if (SortedSet.class.isAssignableFrom(declaredType))
            {
                return TreeSet.class;
            }
            else if (SortedMap.class.isAssignableFrom(declaredType))
            {
                return TreeMap.class;
            }
            else if (List.class.isAssignableFrom(declaredType))
            {
                return ArrayList.class;
            }
            else if (Set.class.isAssignableFrom(declaredType))
            {
                return HashSet.class;
            }
            else if (Map.class.isAssignableFrom(declaredType))
            {
                return HashMap.class;
            }
            else if (ordered)
            {
                return ArrayList.class;
            }
            else
            {
                return HashSet.class;
            }
        }
        return declaredType;
    }

    /**
     * Convenience accessor for whether to detach SCO objects as wrapped.
     * @param ownerSM StateManager
     * @return Whether to detach SCOs in wrapped form
     */
    public static boolean detachAsWrapped(DNStateManager ownerSM)
    {
        return ownerSM.getExecutionContext().getBooleanProperty(PropertyNames.PROPERTY_DETACH_AS_WRAPPED);
    }

    /**
     * Convenience method to return if we should use a queued update for the current operation.
     * @param sm StateManager
     * @return Whether to use queued for this operation
     */
    public static boolean useQueuedUpdate(DNStateManager sm)
    {
        return sm != null && sm.getExecutionContext().operationQueueIsActive();
    }

    /**
     * Method to return if the member is a collection/array with dependent element.
     * @param mmd member metadata
     * @return whether it has dependent element
     */
    public static boolean hasDependentElement(AbstractMemberMetaData mmd)
    {
        if (!SCOUtils.collectionHasElementsWithoutIdentity(mmd) && mmd.getCollection() != null && mmd.getCollection().isDependentElement())
        {
            return true;
        }
        return false;
    }

    /**
     * Method to return if the member is a map with dependent key.
     * @param mmd member metadata
     * @return whether it has dependent key
     */
    public static boolean hasDependentKey(AbstractMemberMetaData mmd)
    {
        if (!SCOUtils.mapHasKeysWithoutIdentity(mmd) && mmd.getMap() != null && mmd.getMap().isDependentKey())
        {
            return true;
        }
        return false;
    }

    /**
     * Method to return if the member is a map with dependent value.
     * @param mmd member metadata
     * @return whether it has dependent value
     */
    public static boolean hasDependentValue(AbstractMemberMetaData mmd)
    {
        if (!SCOUtils.mapHasValuesWithoutIdentity(mmd) && mmd.getMap() != null && mmd.getMap().isDependentValue())
        {
            return true;
        }
        return false;
    }

    /**
     * Convenience method to return if two collections of persistent elements are equal.
     * @param api ApiAdapter
     * @param oldColl Old collection
     * @param newColl New collection
     * @return Whether they are equal
     */
    public static boolean collectionsAreEqual(ApiAdapter api, Collection oldColl, Collection newColl)
    {
        if (oldColl == null && newColl == null)
        {
            return true;
        }
        else if (oldColl == null || newColl == null)
        {
            return false;
        }
        else if (oldColl.size() != newColl.size())
        {
            return false;
        }

        Iterator oldIter = oldColl.iterator();
        Iterator newIter = newColl.iterator();
        while (oldIter.hasNext())
        {
            Object oldVal = oldIter.next();
            Object newVal = newIter.next();
            if (oldVal == null && newVal == null)
            {
                // Same element
            }
            else if (oldVal == null || newVal == null)
            {
                // One is null and the other not, so different
                return false;
            }
            else
            {
                if (api.isPersistable(oldVal))
                {
                    Object oldId = api.getIdForObject(oldVal);
                    Object newId = api.getIdForObject(newVal);
                    if (oldId == null || newId == null)
                    {
                        // One is not persistent so just return false
                        return false;
                    }

                    if (!oldId.equals(newId))
                    {
                        return false;
                    }
                    // Id is the same so element is the same
                }
                else
                {
                    if (!oldVal.equals(newVal))
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }
    
    /**
     * Copy a value if it's an *known* SCO type.
     * @param scoValue An object that might be or not an SCO value
     * @return Return a copy of the value if it's a know SCO type otherwise just return the value itself.
     */
    public static Object copyValue(Object scoValue)
    {
        if (scoValue == null)
        {
            return null;
        }
        else if (scoValue instanceof StringBuffer)
        {
            // Use our own copy of this mutable type
            return new StringBuffer(((StringBuffer)scoValue).toString());
        }
        else if (scoValue instanceof StringBuilder)
        {
            // Use our own copy of this mutable type
            return new StringBuilder(((StringBuilder)scoValue).toString());
        }
        else if (scoValue instanceof Date)
        {
            // Use our own copy of this mutable type
            return ((Date)scoValue).clone();
        }
        else if (scoValue instanceof Calendar)
        {
            // Use our own copy of this mutable type
            return ((Calendar)scoValue).clone();
        }
        
        return scoValue;
    }
    
    public static Object singleCollectionValue(TypeManager typeManager, Object pc)
    {
        if (pc == null) {
            return null;
        }
        Iterator iterator = typeManager.getContainerAdapter(pc).iterator();
        return pc = iterator.hasNext() ? iterator.next() : null;
    }
    
}