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
package org.datanucleus.store.types.wrappers;

import java.io.ObjectStreamException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.datanucleus.FetchPlanState;
import org.datanucleus.flush.CollectionAddOperation;
import org.datanucleus.flush.CollectionRemoveOperation;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.state.RelationshipManager;
import org.datanucleus.store.types.SCOCollection;
import org.datanucleus.store.types.SCOCollectionIterator;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * A mutable second-class HashSet object.
 * This is the simplified form that intercepts mutators and marks the field as dirty.
 * It also handles cascade-delete triggering for persistable elements.
 */
public class HashSet<E> extends java.util.HashSet<E> implements SCOCollection<java.util.HashSet<E>, E>
{
    protected transient ObjectProvider ownerOP;
    protected transient AbstractMemberMetaData ownerMmd;

    /** The internal "delegate". */
    protected java.util.HashSet<E> delegate;

    /**
     * Constructor, using StateManager of the "owner" and the field name.
     * @param ownerOP The owner ObjectProvider
     * @param mmd Metadata for the member
     */
    public HashSet(ObjectProvider ownerOP, AbstractMemberMetaData mmd)
    {
        this.ownerOP = ownerOP;
        this.ownerMmd = mmd;
    }

    public void initialise(java.util.HashSet<E> newValue, Object oldValue)
    {
        initialise(newValue);
    }

    public void initialise(java.util.HashSet c)
    {
        if (c != null)
        {
            delegate = c;
        }
        else
        {
            delegate = new java.util.HashSet();
        }
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("023003", this.getClass().getName(), ownerOP.getObjectAsPrintable(), ownerMmd.getName(), "" + size(), 
                SCOUtils.getSCOWrapperOptionsMessage(true, false, true, false)));
        }
    }

    public void initialise()
    {
        initialise(null);
    }

    // ----------------------- Implementation of SCO methods -------------------

    /**
     * Accessor for the unwrapped value that we are wrapping.
     * @return The unwrapped value
     */
    public java.util.HashSet<E> getValue()
    {
        return delegate;
    }

    public void setValue(java.util.HashSet<E> value)
    {
        this.delegate = value;
    }

    /**
     * Method to effect the load of the data in the SCO.
     * Used when the SCO supports lazy-loading to tell it to load all now.
     */
    public void load()
    {
        // Always loaded
    }

    /**
     * Method to return if the SCO has its contents loaded. Returns true.
     * @return Whether it is loaded
     */
    public boolean isLoaded()
    {
        return true;
    }

    /**
     * Method to update an embedded element in this collection.
     * @param element The element
     * @param fieldNumber Number of field in the element
     * @param value New value for this field
     * @param makeDirty Whether to make the SCO field dirty.
     */
    public void updateEmbeddedElement(E element, int fieldNumber, Object value, boolean makeDirty)
    {
        if (makeDirty)
        {
            // Just mark field in embedded owners as dirty
            makeDirty();
        }
    }

    /**
     * Accessor for the field name.
     * @return The field name
     */
    public String getFieldName()
    {
        return ownerMmd.getName();
    }

    /**
     * Accessor for the owner object.
     * @return The owner object
     */
    public Object getOwner()
    {
        return ownerOP != null ? ownerOP.getObject() : null;
    }

    /**
     * Method to unset the owner and field information.
     */
    public void unsetOwner()
    {
        if (ownerOP != null)
        {
            ownerOP = null;
            ownerMmd = null;
        }
    }

    /**
     * Utility to mark the object as dirty
     */
    public void makeDirty()
    {
        if (ownerOP != null)
        {
            ownerOP.makeDirty(ownerMmd.getAbsoluteFieldNumber());
        }
    }

    /**
     * Method to return a detached copy of the container.
     * Recurses through the elements so that they are likewise detached.
     * @param state State for detachment process
     * @return The detached container
     */
    public java.util.HashSet detachCopy(FetchPlanState state)
    {
        java.util.HashSet detached = new java.util.HashSet();
        SCOUtils.detachCopyForCollection(ownerOP, toArray(), state, detached);
        return detached;
    }

    /**
     * Method to return an attached copy of the passed (detached) value. 
     * The returned attached copy is a SCO wrapper. 
     * Goes through the existing elements in the store for this owner field and removes ones no longer present, and adds new elements. 
     * All elements in the (detached) value are attached.
     * @param value The new (collection) value
     */
    public void attachCopy(java.util.HashSet value)
    {
        SCOUtils.attachCopyElements(ownerOP, this, value, SCOUtils.collectionHasElementsWithoutIdentity(ownerMmd));
    }

    // ------------------ Implementation of HashSet methods --------------------

    /**
     * Creates and returns a copy of this object.
     * @return The cloned object
     */
    public Object clone()
    {
        return delegate.clone();
    }

    /**
     * Accessor for whether an element is contained in this set.
     * @param element The element
     * @return Whether it is contained.
     */
    public boolean contains(Object element)
    {
        return delegate.contains(element);
    }

    /**
     * Accessor for whether a collection is contained in this set.
     * @param c The collection
     * @return Whether it is contained.
     */
    public boolean containsAll(java.util.Collection c)
    {
        return delegate.containsAll(c);
    }

    public boolean equals(Object o)
    {
        return delegate.equals(o);
    }

    public int hashCode()
    {
        return delegate.hashCode();
    }

    /**
     * Accessor for whether the HashSet is empty.
     * @return Whether it is empty.
     */
    public boolean isEmpty()
    {
        return delegate.isEmpty();
    }

    /**
     * Accessor for an iterator for the Set.
     * @return The iterator
     */
    public Iterator<E> iterator()
    {
        return new SCOCollectionIterator(this, ownerOP, delegate, null, true);
    }

    /**
     * Accessor for the size of the HashSet.
     * @return The size.
     **/
    public int size()
    {
        return delegate.size();
    }

    /**
     * Method to return the list as an array.
     * @return The array
     **/
    public Object[] toArray()
    {
        return delegate.toArray();
    }

    /**
     * Method to return the list as an array.
     * @param a The runtime types of the array being defined by this param
     * @return The array
     **/
    public Object[] toArray(Object a[])
    {
        return delegate.toArray(a);
    }

    /**
     * Method to add an element to the set.
     * @param element The new element
     * @return Whether it was added ok.
     **/
    public boolean add(E element)
    {
        boolean success = delegate.add(element);
        if (ownerOP != null && ownerOP.getExecutionContext().getManageRelations())
        {
            // Relationship management
            ownerOP.getExecutionContext().getRelationshipManager(ownerOP).relationAdd(ownerMmd.getAbsoluteFieldNumber(), element);
        }
        if (success)
        {
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                ownerOP.getExecutionContext().addOperationToQueue(new CollectionAddOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), element));
            }
            makeDirty();
            if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
            {
                ownerOP.getExecutionContext().processNontransactionalUpdate();
            }
        }
        return success;
    }

    /**
     * Method to add a collection to the set.
     * @param c The collection
     * @return Whether it was added ok.
     **/
    public boolean addAll(Collection c)
    {
        boolean success = delegate.addAll(c);
        if (ownerOP != null && ownerOP.getExecutionContext().getManageRelations())
        {
            // Relationship management
            Iterator iter = c.iterator();
            while (iter.hasNext())
            {
                ownerOP.getExecutionContext().getRelationshipManager(ownerOP).relationAdd(ownerMmd.getAbsoluteFieldNumber(), iter.next());
            }
        }
        if (success)
        {
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                for (Object element : c)
                {
                    ownerOP.getExecutionContext().addOperationToQueue(new CollectionAddOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), element));
                }
            }
            makeDirty();
            if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
            {
                ownerOP.getExecutionContext().processNontransactionalUpdate();
            }
        }
        return success;
    }

    /**
     * Method to clear the HashSet
     **/
    public void clear()
    {
        if (ownerOP != null && ownerOP.getExecutionContext().getManageRelations())
        {
            // Relationship management
            Iterator iter = delegate.iterator();
            RelationshipManager relMgr = ownerOP.getExecutionContext().getRelationshipManager(ownerOP);
            while (iter.hasNext())
            {
                relMgr.relationRemove(ownerMmd.getAbsoluteFieldNumber(), iter.next());
            }
        }

        if (ownerOP != null && !delegate.isEmpty())
        {
            // Cascade delete
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                // Queue any cascade delete
                Iterator iter = delegate.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), iter.next(), true));
                }
            }
            else if (SCOUtils.hasDependentElement(ownerMmd))
            {
                // Perform the cascade delete
                Iterator iter = delegate.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().deleteObjectInternal(iter.next());
                }
            }
        }

        delegate.clear();

        makeDirty();
        if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
        {
            ownerOP.getExecutionContext().processNontransactionalUpdate();
        }
    }

    /**
     * Method to remove an element from the set
     * @param element The Element to remove
     * @return Whether it was removed successfully.
     */
    public boolean remove(Object element)
    {
        return remove(element, true);
    }

    /**
     * Method to remove an element from the set
     * @param element The Element to remove
     * @return Whether it was removed successfully.
     */
    public boolean remove(Object element, boolean allowCascadeDelete)
    {
        boolean success = delegate.remove(element);
        if (ownerOP != null && ownerOP.getExecutionContext().getManageRelations())
        {
            // Relationship management
            ownerOP.getExecutionContext().getRelationshipManager(ownerOP).relationRemove(ownerMmd.getAbsoluteFieldNumber(), element);
        }

        if (ownerOP != null && allowCascadeDelete)
        {
            // Cascade delete
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                // Queue any cascade delete
                ownerOP.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), element, allowCascadeDelete));
            }
            else if (SCOUtils.hasDependentElement(ownerMmd))
            {
                // Perform the cascade delete
                ownerOP.getExecutionContext().deleteObjectInternal(element);
            }
        }

        if (success)
        {
            makeDirty();
            if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
            {
                ownerOP.getExecutionContext().processNontransactionalUpdate();
            }
        }

        return success;
    }

    /**
     * Method to remove all elements from the collection from the set.
     * @param elements The collection of elements to remove 
     * @return Whether it was removed ok.
     */
    public boolean removeAll(java.util.Collection elements)
    {
        if (elements == null)
        {
            throw new NullPointerException();
        }
        else if (elements.isEmpty())
        {
            return true;
        }

        boolean success = delegate.removeAll(elements);

        if (ownerOP != null)
        {
            if (ownerOP.getExecutionContext().getManageRelations())
            {
                // Relationship management
                Iterator iter = elements.iterator();
                RelationshipManager relMgr = ownerOP.getExecutionContext().getRelationshipManager(ownerOP);
                while (iter.hasNext())
                {
                    relMgr.relationRemove(ownerMmd.getAbsoluteFieldNumber(), iter.next());
                }
            }

            // Cascade delete
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                // Queue any cascade delete
                Iterator iter = elements.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), iter.next(), true));
                }
            }
            else if (SCOUtils.hasDependentElement(ownerMmd))
            {
                // Perform the cascade delete
                Iterator iter = elements.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().deleteObjectInternal(iter.next());
                }
            }
        }

        if (success)
        {
            makeDirty();
            if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
            {
                ownerOP.getExecutionContext().processNontransactionalUpdate();
            }
        }

        return success;
    }

    /**
     * Method to retain a Collection of elements (and remove all others).
     * @param c The collection to retain
     * @return Whether they were retained successfully.
     */
    public boolean retainAll(java.util.Collection c)
    {
        if (c == null)
        {
            throw new NullPointerException("Input collection was null");
        }
        Collection collToRemove = new java.util.HashSet();
        for (Object o : delegate)
        {
            if (!c.contains(o))
            {
                collToRemove.add(o);
            }
        }

        boolean success = delegate.retainAll(c);
        if (success)
        {
            makeDirty();
            if (SCOUtils.useQueuedUpdate(ownerOP))
            {
                // Queue any cascade delete
                Iterator iter = collToRemove.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerOP, ownerMmd.getAbsoluteFieldNumber(), iter.next(), true));
                }
            }
            else if (SCOUtils.hasDependentElement(ownerMmd))
            {
                // Perform the cascade delete
                Iterator iter = collToRemove.iterator();
                while (iter.hasNext())
                {
                    ownerOP.getExecutionContext().deleteObjectInternal(iter.next());
                }
            }

            if (ownerOP != null && !ownerOP.getExecutionContext().getTransaction().isActive())
            {
                ownerOP.getExecutionContext().processNontransactionalUpdate();
            }
        }
        return success;
    }

    /**
     * The writeReplace method is called when ObjectOutputStream is preparing
     * to write the object to the stream. The ObjectOutputStream checks
     * whether the class defines the writeReplace method. If the method is
     * defined, the writeReplace method is called to allow the object to
     * designate its replacement in the stream. The object returned should be
     * either of the same type as the object passed in or an object that when
     * read and resolved will result in an object of a type that is compatible
     * with all references to the object.
     * @return the replaced object
     * @throws ObjectStreamException if an error occurs
     */
    protected Object writeReplace() throws ObjectStreamException
    {
        return new java.util.HashSet(delegate);
    }

    /* (non-Javadoc)
     * @see java.lang.Iterable#forEach(java.util.function.Consumer)
     */
    @Override
    public void forEach(Consumer<? super E> action)
    {
        delegate.forEach(action);
    }

    /* (non-Javadoc)
     * @see java.util.Collection#stream()
     */
    @Override
    public Stream stream()
    {
        return delegate.stream();
    }

    /* (non-Javadoc)
     * @see java.util.Collection#parallelStream()
     */
    @Override
    public Stream parallelStream()
    {
        return delegate.parallelStream();
    }

    /* (non-Javadoc)
     * @see java.util.HashSet#spliterator()
     */
    @Override
    public Spliterator spliterator()
    {
        return delegate.spliterator();
    }
}