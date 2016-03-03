/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.hawt.osgi.jmx;

import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.apache.commons.codec.binary.Hex;
import org.apache.karaf.management.JMXSecurityMBean;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An MBean that may be used by <code>hawtio:type=security,name=RBACRegistry</code> to decorate optimized
 * jolokia <code>list</code> operation with RBAC info.</p>
 */
public class RBACDecorator implements RBACDecoratorMBean {

    public static Logger LOG = LoggerFactory.getLogger(RBACDecorator.class);

    private static final String JMX_ACL_PID_PREFIX = "jmx.acl";
    private static final String JMX_OBJECTNAME_PROPERTY_WILDCARD = "_";
    private static final Comparator<String[]> WILDCARD_PID_COMPARATOR = new WildcardPidComparator();

    private final BundleContext bundleContext;

    private ObjectName objectName;
    private MBeanServer mBeanServer;

    public RBACDecorator(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    void init() throws Exception {
        if (objectName == null) {
            objectName = new ObjectName("hawtio:type=security,area=jolokia,name=RBACDecorator");
        }
        if (mBeanServer == null) {
            mBeanServer = ManagementFactory.getPlatformMBeanServer();
        }
        mBeanServer.registerMBean(this, objectName);
    }

    void destroy() throws Exception {
        if (objectName != null && mBeanServer != null) {
            mBeanServer.unregisterMBean(objectName);
        }
    }

    /**
     * If we have access to {@link ConfigurationAdmin}, we can add RBAC information
     * @param result
     */
    @Override
    @SuppressWarnings("unchecked")
    public void decorate(Map<String, Object> result) throws Exception {
        try {
            ServiceReference<ConfigurationAdmin> cmRef = bundleContext.getServiceReference(ConfigurationAdmin.class);
            ServiceReference<JMXSecurityMBean> jmxSecRef = bundleContext.getServiceReference(JMXSecurityMBean.class);
            if (cmRef != null && jmxSecRef != null) {
                ConfigurationAdmin configAdmin = bundleContext.getService(cmRef);
                JMXSecurityMBean jmxSec = bundleContext.getService(jmxSecRef);
                if (configAdmin != null && jmxSec != null) {
                    // 1. each pair of MBean/operation has to be marked with RBAC flag (can/can't invoke)
                    // 2. the information is provided by org.apache.karaf.management.JMXSecurityMBean.canInvoke(java.util.Map)
                    // 3. we'll peek into available configadmin jmx.acl* configs, to see which MBeans/operations have to
                    //    be examined and which will produce same results
                    // 4. only then we'll prepare Map as parameter for canInvoke()

                    Configuration[] configurations = configAdmin.listConfigurations("(service.pid=jmx.acl*)");
                    List<String> allJmxAclPids = new LinkedList<>();
                    for (Configuration cfg : configurations) {
                        allJmxAclPids.add(cfg.getPid());
                    }
                    if (allJmxAclPids.size() == 0) {
                        return;
                    }

                    Map<String, Map<String, Object>> domains = (Map<String, Map<String, Object>>) result.get("domains");

                    // cache contains MBeanInfos for different MBeans/ObjectNames
                    Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) result.get("cache");
                    // new cache will contain MBeanInfos + RBAC info
                    Map<String, Map<String, Object>> rbacCache = new HashMap<>();

                    // the fact that some MBeans share JSON MBeanInfo doesn't mean that they can share RBAC info
                    // - each MBean's name may have RBAC information configured in different PIDs.

                    // when iterating through all reapeating MBeans that share MBeanInfo (that doesn't have RBAC info
                    // yet), we have to decide if it'll use shared info after RBAC check or will switch to dedicated
                    // info. we have to be careful not to end with most MBeans *not* sharing MBeanInfo (in case if
                    // somehow the shared info will be "special case" from RBAC point of view)

                    Map<String, List<String>> queryForMBeans = new HashMap<>();
                    Map<String, List<String>> queryForMBeanOperations = new HashMap<>();

                    for (String domain : domains.keySet()) {
                        Map<String, Object> domainMBeansCheck = new HashMap<>(domains.get(domain));
                        Map<String, Object> domainMBeans = domains.get(domain);
                        for (String name : domainMBeansCheck.keySet()) {
                            Object mBeanInfo = domainMBeansCheck.get(name);
                            String fullName = domain + ":" + name;
                            ObjectName n = new ObjectName(fullName);
                            if (mBeanInfo instanceof Map) {
                                // not shared JSONified MBeanInfo
                                prepareKarafRbacInvocations(fullName, (Map<String, Object>) mBeanInfo,
                                        queryForMBeans, queryForMBeanOperations);
                            } else /*if (mBeanInfo instanceof String)*/{
                                // shared JSONified MBeanInfo

                                // shard mbeanNames sharing MBeanInfo by the hierarchy of jmx.acl* PIDs used to
                                // check RBAC info
                                String key = (String) mBeanInfo;
                                String pidListKey = pidListKey(allJmxAclPids, n);
                                if (!rbacCache.containsKey(key + ":" + pidListKey)) {
                                    // shallow copy - we can share op/not/attr/desc, but we put specific
                                    // canInvoke/opByString keys
                                    HashMap<String, Object> sharedMBeanAndRbacInfo = new HashMap<>(cache.get(key));
                                    rbacCache.put(key + ":" + pidListKey, sharedMBeanAndRbacInfo);
                                    // we'll be checking RBAC only for single (first) MBean having this pidListKey
                                    prepareKarafRbacInvocations(fullName, sharedMBeanAndRbacInfo,
                                            queryForMBeans, queryForMBeanOperations);
                                }
                                // switch key from shared MBeanInfo-only to shared MBean+RbacInfo
                                domainMBeans.put(name, key + ":" + pidListKey);
                            }
                        }
                    }

                    // RBAC per MBeans (can invoke *any* operation or attribute?)
                    TabularData dataForMBeans = jmxSec.canInvoke(queryForMBeans);
                    Collection<?> results = dataForMBeans.values();
                    for (Object cd : results) {
                        ObjectName objectName = new ObjectName((String) ((CompositeData) cd).get("ObjectName"));
                        boolean canInvoke = ((CompositeData) cd).get("CanInvoke") != null ? (Boolean) ((CompositeData) cd).get("CanInvoke") : false;
                        Object mBeanInfoOrKey = domains.get(objectName.getDomain()).get(objectName.getKeyPropertyListString());
                        Map<String, Object> mBeanInfo;
                        if (mBeanInfoOrKey instanceof Map) {
                            mBeanInfo = (Map<String, Object>) mBeanInfoOrKey;
                        } else /*if (mBeanInfoOrKey instanceof String) */{
                            mBeanInfo = rbacCache.get(mBeanInfoOrKey.toString());
                        }
                        if (mBeanInfo != null) {
                            mBeanInfo.put("canInvoke", canInvoke);
                        }
                    }

                    // RBAC per { MBean,operation } (can invoke status for each operation)
                    TabularData dataForMBeanOperations = jmxSec.canInvoke(queryForMBeanOperations);
                    results = dataForMBeanOperations.values();
                    for (Object cd : results) {
                        ObjectName objectName = new ObjectName((String) ((CompositeData) cd).get("ObjectName"));
                        String method = (String) ((CompositeData) cd).get("Method");
                        boolean canInvoke = ((CompositeData) cd).get("CanInvoke") != null ? (Boolean) ((CompositeData) cd).get("CanInvoke") : false;
                        Object mBeanInfoOrKey = domains.get(objectName.getDomain()).get(objectName.getKeyPropertyListString());
                        Map<String, Object> mBeanInfo;
                        if (mBeanInfoOrKey instanceof Map) {
                            mBeanInfo = (Map<String, Object>) mBeanInfoOrKey;
                        } else /*if (mBeanInfoOrKey instanceof String) */{
                            mBeanInfo = rbacCache.get(mBeanInfoOrKey.toString());
                        }
                        if (mBeanInfo != null) {
                            ((Map<String, Object>)((Map<String, Object>) mBeanInfo.get("opByString")).get(method)).put("canInvoke", canInvoke);
                        }
                    }

                    result.remove("cache");
                    result.put("cache", rbacCache);
                }
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            // simply do not decorate
        }
    }

    /**
     * Using JSONinified {@link MBeanInfo} prepares arguments for Karaf's canInvoke(Map) invocations
     * @param fullName
     * @param mBeanInfo
     * @param queryForMBeans
     * @param queryForMBeanOperations
     */
    @SuppressWarnings("unchecked")
    private void prepareKarafRbacInvocations(String fullName, /*inout*/Map<String, Object> mBeanInfo,
                                             Map<String, List<String>> queryForMBeans,
                                             Map<String, List<String>> queryForMBeanOperations) {
        queryForMBeans.put(fullName, new ArrayList<String>());
        List<String> operations = operations((Map<String, Object>) mBeanInfo.get("op"));
        // prepare opByString for MBeainInfo
        HashMap<String, Object> opByString = new HashMap<>();
        mBeanInfo.put("opByString", opByString);
        if (operations.size() > 0) {
            queryForMBeanOperations.put(fullName, operations);
            for (String op : operations) {
                // ! no need to copy relevant map for "op['opname']" - hawtio uses only 'canInvoke' property
                opByString.put(op, new HashMap<String, Object>());
            }
        }
    }

    /**
     * Converts {@link ObjectName} to a key that helps verifying whether different MBeans can produce same RBAC info
     * @param allJmxAclPids
     * @param n
     * @return
     */
    public static String pidListKey(List<String> allJmxAclPids, ObjectName n) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        List<String> pidCandidates = iterateDownPids(nameSegments(n));

        MessageDigest md = MessageDigest.getInstance("MD5");
        for (String pc : pidCandidates) {
            String generalPid = getGeneralPid(allJmxAclPids, pc);
            if (generalPid.length() > 0) {
                md.update(generalPid.getBytes("UTF-8"));
            }
        }
        return Hex.encodeHexString(md.digest());
    }

    /**
     * Prepares list of operation signatures to pass to {@link JMXSecurityMBean#canInvoke(Map)}
     * @param ops
     * @return
     */
    @SuppressWarnings("unchecked")
    private List<String> operations(Map<String, Object> ops) {
        List<String> result = new LinkedList<>();
        for (String operation : ops.keySet()) {
            Object operationOrListOfOperations = ops.get(operation);
            List<Map<String, Object>> toStringify;
            if (operationOrListOfOperations instanceof List) {
                toStringify = (List<Map<String, Object>>) operationOrListOfOperations;
            } else /*if (operationOrListOfOperations instanceof Map) */{
                toStringify = Collections.singletonList((Map<String, Object>) operationOrListOfOperations);
            }
            for (Map<String, Object> op : toStringify) {
                List<Map<String, String>> args = (List<Map<String, String>>) op.get("args");
                if (args == null || args.size() == 0) {
                    result.add(operation + "()");
                } else {
                    StringWriter sw = null;
                    for (Map<String, String> arg : args) {
                        if (sw == null) {
                            sw = new StringWriter();
                        } else {
                            sw.append(',');
                        }
                        sw.append(arg.get("type"));
                    }
                    result.add(operation + "(" + sw.toString() + ")");
                }
            }
        }

        return result;
    }

    /**
     * see: <code>org.apache.karaf.management.KarafMBeanServerGuard#getNameSegments(javax.management.ObjectName)</code>
     *
     * Assuming <strong>full</strong> {@link ObjectName} (not null, not containing wildcards and other funny stuff),
     * split objectName to elements used then co contruct ordered list of PIDs to check for MBean permissions.
     * @return
     */
    public static List<String> nameSegments(ObjectName objectName) {
        List<String> segments = new ArrayList<>();
        segments.add(objectName.getDomain());
        for (String s : objectName.getKeyPropertyListString().split(",")) {
            int index = s.indexOf('=');
            if (index < 0) {
                continue;
            }
            String key = objectName.getKeyProperty(s.substring(0, index));
            if (s.substring(0, index).equals("type")) {
                segments.add(1, key);
            } else {
                segments.add(key);
            }
        }

        return segments;
    }

    /**
     * see: <code>org.apache.karaf.management.KarafMBeanServerGuard#iterateDownPids(java.util.List)</code>
     *
     * Given a list of segments, return a list of PIDs that are searched in this order.
     * For example, given the following segments: org.foo, bar, test
     * the following list of PIDs will be generated (in this order):
     *      jmx.acl.org.foo.bar.test
     *      jmx.acl.org.foo.bar
     *      jmx.acl.org.foo
     *      jmx.acl
     * The order is used as a search order, in which the most specific PID is searched first.
     * Assume that none of the segments contain special/wildcard values.
     *
     * @param segments the ObjectName segments.
     * @return the PIDs corresponding with the ObjectName in the above order.
     */
    public static List<String> iterateDownPids(List<String> segments) {
        List<String> res = new ArrayList<>();
        for (int i = segments.size(); i > 0; i--) {
            StringBuilder sb = new StringBuilder();
            sb.append(JMX_ACL_PID_PREFIX);
            for (int j = 0; j < i; j++) {
                sb.append('.');
                sb.append(segments.get(j));
            }
            res.add(sb.toString());
        }
        res.add(JMX_ACL_PID_PREFIX); // this is the top PID (aka jmx.acl)
        return res;
    }

    /**
     * <p>see: <code>org.apache.karaf.management.KarafMBeanServerGuard#getGeneralPid(java.util.List, java.lang.String)</code></p>
     *
     * <p>Given a list of all available configadmin PIDs that define RBAC information, return a real PID that'll be
     * used to fetch information about particular, non wildcard <code>pid</code>.</p>
     *
     * <p>Here, the PID returned may use wildcards ("_"), which means that general PID will be used to check particular
     * <code>pid</code> (one of possible PIDs derived from {@link ObjectName})</p>
     * @param allJmxAclPids
     * @param pid one of the PIDs returned from {@link #iterateDownPids(List)}
     * @return
     */
    private static String getGeneralPid(List<String> allJmxAclPids, String pid) {
        String[] pidStrArray = pid.split(Pattern.quote("."));
        Set<String[]> rets = new TreeSet<>(WILDCARD_PID_COMPARATOR);
        for (String id : allJmxAclPids) {
            String[] idStrArray = id.split(Pattern.quote("."));
            if (idStrArray.length == pidStrArray.length) {
                boolean match = true;
                for (int i = 0; i < idStrArray.length; i++) {
                    if (!(idStrArray[i].equals(JMX_OBJECTNAME_PROPERTY_WILDCARD)
                            || idStrArray[i].equals(pidStrArray[i]))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    rets.add(idStrArray);
                }
            }
        }

        Iterator<String[]> it = rets.iterator();
        if (!it.hasNext()) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            for (String segment : it.next()) {
                if (buffer.length() > 0) {
                    buffer.append(".");
                }
                buffer.append(segment);
            }
            return buffer.toString();
        }
    }

    /**
     * Checks if two {@link ObjectName}s may share RBAC info - if the same configadmin PIDs are examined by Karaf
     * @param realJmxAclPids
     * @param o1
     * @param o2
     * @return
     */
    public static boolean mayShareRBACInfo(List<String> realJmxAclPids, ObjectName o1, ObjectName o2) {
        if (o1 == null || o2 == null) {
            return false;
        }

        Deque<String> pids1 = new LinkedList<>();
        List<String> pidCandidates1 = iterateDownPids(nameSegments(o1));
        List<String> pidCandidates2 = iterateDownPids(nameSegments(o2));

        for (String pidCandidate1 : pidCandidates1) {
            pids1.add(getGeneralPid(realJmxAclPids, pidCandidate1));
        }
        for (String pidCandidate2 : pidCandidates2) {
            if (pids1.peek() == null || !pids1.pop().equals(getGeneralPid(realJmxAclPids, pidCandidate2))) {
                return false;
            }
        }

        return pids1.size() == 0;
    }

    /**
     * <code>nulls</code>-last comparator of PIDs split to segments. {@link #JMX_OBJECTNAME_PROPERTY_WILDCARD}
     * in a segment makes the PID more generic, thus - with lower prioroty.
     */
    private static class WildcardPidComparator implements Comparator<String[]> {
        @Override
        public int compare(String[] o1, String[] o2) {
            if (o1 == null && o2 == null) {
                return 0;
            }
            if (o1 == null) {
                return 1;
            }
            if (o2 == null) {
                return -1;
            }
            if (o1.length != o2.length) {
                // not necessary - not called with PIDs of different segment count
                return o1.length - o2.length;
            }
            for (int n = 0; n < o1.length; n++) {
                if (o1[n].equals(o2[n])) {
                    continue;
                }
                if (o1[n].equals(JMX_OBJECTNAME_PROPERTY_WILDCARD)) {
                    return 1;
                }
                if (o2[n].equals(JMX_OBJECTNAME_PROPERTY_WILDCARD)) {
                    return -1;
                }
                return o1[n].compareTo(o2[n]);
            }
            return 0;
        }
    }

}
