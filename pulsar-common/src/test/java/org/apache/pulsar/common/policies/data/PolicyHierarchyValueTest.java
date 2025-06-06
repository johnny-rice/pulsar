/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.policies.data;

import org.testng.Assert;
import org.testng.annotations.Test;


public class PolicyHierarchyValueTest {
    @Test
    public void testPolicyValue() {
        PolicyHierarchyValue<Integer> value = new PolicyHierarchyValue<>();

        value.updateBrokerValue(1);
        Assert.assertEquals(value.get(), Integer.valueOf(1));

        value.updateNamespaceValue(2);
        Assert.assertEquals(value.get(), Integer.valueOf(2));

        value.updateTopicValue(3, false);
        Assert.assertEquals(value.get(), Integer.valueOf(3));

        value.updateNamespaceValue(null);
        Assert.assertEquals(value.get(), Integer.valueOf(3));

        value.updateTopicValue(null, false);
        Assert.assertEquals(value.get(), Integer.valueOf(1));
    }
}