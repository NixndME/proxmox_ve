package com.morpheusdata.proxmox.ve.util

import groovy.util.logging.Slf4j
import java.net.InetAddress

@Slf4j
class ProxmoxMiscUtil {

    static String getNetworkAddress(String ipWithCidr) {
        def (ip, cidr) = ipWithCidr.tokenize('/')
        int prefixLength = cidr.toInteger()

        byte[] ipBytes = InetAddress.getByName(ip).address
        byte[] subnetMask = new byte[ipBytes.length]
        for (int i = 0; i < prefixLength; i++) {
            subnetMask[i / 8] |= (1 << (7 - (i % 8)))
        }

        byte[] networkBytes = new byte[ipBytes.length]
        for (int i = 0; i < ipBytes.length; i++) {
            networkBytes[i] = (byte) (ipBytes[i] & subnetMask[i])
        }

        String networkAddress = InetAddress.getByAddress(networkBytes).hostAddress
        return "$networkAddress/$prefixLength"
    }
}
