/*
 * RHQ Management Platform
 * Copyright (C) 2005-2011 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.rhq.plugins.apache.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rhq.plugins.apache.ApacheServerComponent;
import org.rhq.plugins.apache.ApacheVirtualHostServiceComponent;
import org.rhq.plugins.apache.parser.ApacheDirectiveTree;

/**
 * A helper class to create legacy resource keys for vhosts.
 *
 * @author Lukas Krejci
 */
public class VirtualHostLegacyResourceKeyUtil {

    private List<VHostSpec> vhosts;
    private List<HttpdAddressUtility.Address> snmpLikeReport;
    private HttpdAddressUtility addressUtility;
    private ApacheDirectiveTree runtimeConfig;
    private ApacheServerComponent serverComponent;
    
    public VirtualHostLegacyResourceKeyUtil(ApacheServerComponent serverComponent, ApacheDirectiveTree runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.addressUtility = serverComponent.getAddressUtility();
        this.serverComponent = serverComponent;
        
        vhosts = VHostSpec.detect(runtimeConfig);
        snmpLikeReport = new ArrayList<HttpdAddressUtility.Address>();
        snmpLikeReport.add(addressUtility.getHttpdInternalMainServerAddressRepresentation(runtimeConfig));
        for(VHostSpec vhost : vhosts) {
            HttpdAddressUtility.Address snmpRecord = addressUtility.getHttpdInternalVirtualHostAddressRepresentation(runtimeConfig, vhost.hosts.get(0), vhost.serverName); 
            snmpLikeReport.add(snmpRecord);
        }
    }
    
    /**
     * This creates a list of possible resource keys for the main server.
     * <p>
     * This uses the algoritms used in the RHQ 3 codebase and returns both the resource key
     * that would be generated wihout SNMP support and with it.
     * <p>
     * The returned list contains either 1 or 2 elements. The first element is always the resource
     * key as it would be generated without SNMP support. The second element is the resource key as 
     * it would be derived from the SNMP record. If the legacy algorithm wouldn't be able to 
     * deduce the resource key from SNMP (which could happen), the second element wouldn't be present
     * in the resulting list.
     * . 
     * @return possible resource keys of the main server.
     */
    public List<String> getLegacyMainServerResourceKeys() {
        String mainServerUrl = serverComponent.getServerUrl();
        
        String key = null;
        
        if (mainServerUrl != null && !"null".equals(mainServerUrl)) {
            try {
                URI mainServerUri = new URI(mainServerUrl);
                String host = mainServerUri.getHost();
                int port = mainServerUri.getPort();
                if (port == -1) {
                    port = 80;
                }
    
                key = host + ":" + port;
            } catch (URISyntaxException e) {
                //hmm.. strange
            }
        } else {
            key = ApacheVirtualHostServiceComponent.MAIN_SERVER_RESOURCE_KEY;
        }
        
        String snmpKey = getSNMPResourceKey(key);

        String[] ret = new String[snmpKey == null ? 1 : 2];
        
        ret[0] = key;
        if (snmpKey != null) {
            ret[1] = snmpKey;
        }
        
        return Arrays.asList(ret);
    }
    
    /**
     * Returns a list of possible resource keys that the RHQ 3 code could generate for the specified
     * vhost.
     * <p>
     * As with {@link #getLegacyMainServerResourceKeys()} the resulting list will contain either 1 or 
     * 2 elements depending on the ability of the legacy algorithm to deduce the resource key from SNMP.
     * 
     * @param vhost
     * @return the possible vhosts
     */
    public List<String> getLegacyVirtualHostResourceKeys(VHostSpec vhost) {
        String host = vhost.hosts.get(0);
        HttpdAddressUtility.Address hostAddr = HttpdAddressUtility.Address.parse(host);
        if (vhost.serverName != null) {
            HttpdAddressUtility.Address serverAddr = HttpdAddressUtility.Address.parse(vhost.serverName);
            hostAddr.host = serverAddr.host;
        }
        
        //the SNMP module seems to resolve the IPs to hostnames.
        try {
            InetAddress hostName = InetAddress.getByName(hostAddr.host);
            hostAddr.host = hostName.getHostName();
        } catch (UnknownHostException e) {
        } 
        
        String key = hostAddr.host + ":" + hostAddr.port;

        String snmpKey = getSNMPResourceKey(key);

        String[] ret = new String[snmpKey == null ? 1 : 2];
        ret[0] = key;
        
        if (snmpKey != null) {
            ret[1] = snmpKey;
        }
               
        return Arrays.asList(ret);
    }
    
    private String getSNMPResourceKey(String nonSnmpResourceKey) {
        int idx = getMatchingWwwServiceIndex(nonSnmpResourceKey);
        
        if (idx < 0) {
            return null;
        } else {
            HttpdAddressUtility.Address snmpRecord = snmpLikeReport.get(idx);
            return snmpRecord.host + ":" + snmpRecord.port;
        }
    }

    /**
     * This is the adaptation of the matching algoritm that was used in the ApacheVirtualHostServiceComponent
     * to match a vhost to an SNMP index based on its resource key.
     * 
     * @param resourceKey
     * @return
     */
    private int getMatchingWwwServiceIndex(String resourceKey) {
        int ret = -1;

        //figure out the servername and addresses of this virtual host
        //from the resource key.
        String vhostServerName = null;
        String[] vhostAddressStrings = null;
        int pipeIdx = resourceKey.indexOf('|');
        if (pipeIdx >= 0) {
            vhostServerName = resourceKey.substring(0, pipeIdx);
        }
        vhostAddressStrings = resourceKey.substring(pipeIdx + 1).split(" ");

        //convert the vhost addresses into fully qualified ip/port addresses
        List<HttpdAddressUtility.Address> vhostAddresses = new ArrayList<HttpdAddressUtility.Address>(
            vhostAddressStrings.length);

        if (vhostAddressStrings.length == 1 && ApacheVirtualHostServiceComponent.MAIN_SERVER_RESOURCE_KEY.equals(vhostAddressStrings[0])) {
            HttpdAddressUtility.Address serverAddr = addressUtility.getMainServerSampleAddress(runtimeConfig, null, 0);
            if (serverAddr != null) {
                vhostAddresses.add(serverAddr);
            }
        } else {
            for (int i = 0; i < vhostAddressStrings.length; ++i) {
                HttpdAddressUtility.Address vhostAddr = addressUtility.getVirtualHostSampleAddress(runtimeConfig, vhostAddressStrings[i],
                    vhostServerName, true);
                if (vhostAddr != null) {
                    vhostAddresses.add(vhostAddr);
                } else {
                    //this is not to choke on the old style resource keys for the main server. without this, we'd never be able
                    //to match the main server with its snmp index below.
                    HttpdAddressUtility.Address addr = HttpdAddressUtility.Address.parse(vhostAddressStrings[i]);
                    vhostAddr = addressUtility.getMainServerSampleAddress(runtimeConfig, addr.host, addr.port);
                    if (vhostAddr != null) {
                        vhostAddresses.add(vhostAddr);
                    }
                }
            }
        }

        //finding the snmp index that corresponds to the address(es) of the vhost isn't that simple
        //because the snmp module in apache always resolves the IPs to hostnames.
        //on the other hand, the resource key tries to be more accurate about what a 
        //vhost can actually be represented as. A vhost is represented by at most 1 hostname (i.e. ServerName)
        //and possibly multiple IP addresses.
        int bestMatchRate = 0;
        
        int idx = 0;
        for(HttpdAddressUtility.Address snmpAddress : snmpLikeReport) {
            
            int matchRate = matchRate(vhostAddresses, snmpAddress);
            if (matchRate > bestMatchRate) {
                ret = idx;
                bestMatchRate = matchRate;
            }
            
            ++idx;
        }
        
        return ret;
    }
    
    private static int matchRate(List<HttpdAddressUtility.Address> addresses, HttpdAddressUtility.Address addressToCheck) {
        for(HttpdAddressUtility.Address a : addresses) {
            if (HttpdAddressUtility.isAddressConforming(addressToCheck, a.host, a.port, true)) {
                return 3;
            }
        }
        
        //try to get the IP of the address to check
        InetAddress[] ipAddresses;
        try {
            ipAddresses = InetAddress.getAllByName(addressToCheck.host);
            for(InetAddress ip : ipAddresses) {
                HttpdAddressUtility.Address newCheck = new HttpdAddressUtility.Address(ip.getHostAddress(), addressToCheck.port);
                
                for(HttpdAddressUtility.Address a : addresses) {
                    if (HttpdAddressUtility.isAddressConforming(newCheck, a.host, a.port, true)) {
                        return 2;
                    }
                }
            }            
        } catch (UnknownHostException e) {
            return 0;
        }
        
        //because of the lack of documentation on the SNMP module, we assumed
        //some wrong things in the past.. this is one of them...
        int addressPort = addressToCheck.port;
        if (addressPort == 80) {
            addressPort = 0;
        }
        
        //ok, try the hardest...
        for(HttpdAddressUtility.Address listAddress: addresses) {
            int listPort = listAddress.port;
            if (listPort == 80) {
                listPort = 0;
            }
            
            InetAddress[] listAddresses;
            try {
                listAddresses = InetAddress.getAllByName(listAddress.host);
            } catch (UnknownHostException e) {
                return 0;
            }
            
            for (InetAddress listInetAddr : listAddresses) {
                for (InetAddress ip : ipAddresses) {
                    if (ip.equals(listInetAddr) && addressPort == listPort) {
                        return 1;
                    }
                }
            }
        }
        
        return 0;
    }    
}
