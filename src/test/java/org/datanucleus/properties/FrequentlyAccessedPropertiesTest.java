/**********************************************************************
Copyright (c) 2014 Kaarel Kann and others. All rights reserved.
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
package org.datanucleus.properties;

import java.util.HashMap;
import java.util.Map;

import org.datanucleus.*;
import org.junit.Assert;

import org.datanucleus.store.AbstractStoreManager;
import org.datanucleus.store.query.Query;
import org.junit.Test;

public class FrequentlyAccessedPropertiesTest
{

    @Test
    public void testConfiguration()
    {
        
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(PropertyNames.PROPERTY_DETACH_ON_CLOSE, "true");
        props.put(PropertyNames.PROPERTY_OPTIMISTIC, "true");
        props.put("datanucleus.storeManagerType", StoreManagerStub.class.getName());

        PersistenceNucleusContextImpl ctx = new PersistenceNucleusContextImpl(null, props) {
            private static final long serialVersionUID = 6287389368679465707L;

            @Override
            public synchronized void initialise() {
            }
        };
        Configuration conf = ctx.getConfiguration();
        
        Assert.assertFalse(conf.getFrequentProperties().getDetachAllOnCommit());
        Assert.assertTrue(conf.getFrequentProperties().getDetachOnClose());
        ExecutionContextPool ecPool = new ExecutionContextPool(ctx);
        ExecutionContext ec = ecPool.checkOut(null, new HashMap<>());
        Assert.assertTrue(ec.getTransaction().getOptimistic());
        ec.setProperty(PropertyNames.PROPERTY_OPTIMISTIC, "false");
        Assert.assertFalse(ec.getTransaction().getOptimistic());
        Assert.assertTrue(conf.getFrequentProperties().getOptimisticTransaction());
    }
    
    @Test
    public void testFrequentlyAccessedProperties()
    {
        FrequentlyAccessedProperties props = new FrequentlyAccessedProperties();
        props.setProperty("xx", new Object());//no op -> no exception
        Assert.assertNull(props.getDetachAllOnCommit());
        props.setProperty(PropertyNames.PROPERTY_DETACH_ALL_ON_COMMIT, "false");
        Assert.assertFalse(props.getDetachAllOnCommit());
        props.setProperty(PropertyNames.PROPERTY_DETACH_ALL_ON_COMMIT, true);
        Assert.assertTrue(props.getDetachAllOnCommit());
        
        FrequentlyAccessedProperties defaults = new FrequentlyAccessedProperties();
        props.setDefaults(defaults);
        
        Assert.assertNull(props.getOptimisticTransaction());
        
        defaults.setProperty(PropertyNames.PROPERTY_OPTIMISTIC, true);
        Assert.assertTrue(props.getOptimisticTransaction());
    }

    public static class StoreManagerStub extends AbstractStoreManager 
    {
        public StoreManagerStub(ClassLoaderResolver clr, PersistenceNucleusContext nucleusContext, Map<String, Object> props)
        {
            super("test", clr, nucleusContext, props);
        }

        @Override
        protected void registerConnectionMgr()
        {
        }

        @Override
        public Query newQuery(String language, ExecutionContext ec)
        {
            return null;
        }

        @Override
        public Query newQuery(String language, ExecutionContext ec, String queryString)
        {
            return null;
        }

        @Override
        public Query newQuery(String language, ExecutionContext ec, Query q)
        {
            return null;
        }

        @Override
        public boolean supportsQueryLanguage(String language)
        {
            return true;
        }
    }
}

