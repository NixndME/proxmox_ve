package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.NetworkIdentityProjection
import groovy.util.logging.Slf4j


@Slf4j
class NetworkSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private Map authConfig

    public NetworkSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@morpheusContext = proxmoxVePlugin.morpheus
        this.@apiClient = apiClient
        this.@authConfig = plugin.getAuthConfig(cloud)
    }



    def execute() {
        try {
            log.debug "BEGIN: execute NetworkSync: ${cloud.id}"

            def cloudItems = ProxmoxApiComputeUtil.listProxmoxNetworks(apiClient, authConfig)
            def domainRecords = morpheusContext.async.network.listIdentityProjections(
                    new DataQuery().withFilter('typeCode', "proxmox.ve.bridge.$cloud.id")
            )

            SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(domainRecords, cloudItems.data)

            syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map network ->
                domainObject?.externalId == network?.iface
            }.onAdd { itemsToAdd ->
                addMissingNetworks(cloud, itemsToAdd)
            }.onDelete { itemsToDelete ->
                removeMissingNetworks(itemsToDelete)
            }.withLoadObjectDetails { updateItems ->
                Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                return morpheusContext.async.network.listById(updateItems?.collect { it.existingItem.id }).map { Network network ->
                    return new SyncTask.UpdateItem<Network, Map>(existingItem: network, masterItem: updateItemMap[network.id].masterItem)
                }             
            }.onUpdate { itemsToUpdate ->
                updateMatchedNetworks(itemsToUpdate)
            }.start()
        } catch(e) {
            log.error "Error in NetworkSync execute : ${e}", e
        }
        log.debug "Execute NetworkSync COMPLETED: ${cloud.id}"
    }


    private addMissingNetworks(Cloud cloud, Collection addList) {
        log.debug "addMissingNetworks: ${cloud.name} ${addList?.size()}"
        
        try {
            def networkType = morpheusContext.async.network.type.list(new DataQuery().withFilter('code', 'proxmox-ve-bridge-network')).blockingFirst()
            def networks = []
            
            for(cloudItem in addList) {
                log.debug("Adding Network: $cloudItem")
                networks << new Network(
                        externalId   : cloudItem.iface,
                        name         : cloudItem.iface,
                        cloud        : cloud,
                        displayName  : cloudItem.name ?: cloudItem.iface,
                        description  : cloudItem.networkAddress,
                        cidr         : cloudItem.networkAddress,
                        status       : cloudItem.active ?: true,
                        code         : "proxmox.network.${cloudItem.iface}",
                        typeCode     : networkType?.code,
                        type         : networkType,
                        owner        : cloud.account,
                        tenantName   : cloud.account.name,
                        refType      : "ComputeZone",
                        refId        : cloud.id,
                        networkServer: cloud.networkServer,
                        providerId   : "",
                        gateway      : cloudItem.gateway,
                        dnsPrimary   : cloudItem.gateway,
                        dnsSecondary : "8.8.8.8",
                        dhcpServer   : true
                )
            }
            
            if (networks) {
                log.debug("Saving ${networks.size()} Networks")
                if (!morpheusContext.async.network.bulkCreate(networks).blockingGet()){
                    log.error "Error saving new networks!"
                }
            }
        } catch(e) {
            log.error "Error in creating networks: ${e}", e
        }
    }


    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateItems) {
        log.debug "updateMatchedNetworks: ${cloud.name} ${updateItems?.size()}"
        
        try {
            def saveList = []
            
            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def doUpdate = false

                // Update status/active state
                def isActive = cloudItem.active != null ? cloudItem.active : true
                if (existingItem.status != isActive) {
                    log.debug("Updating status for ${existingItem.name}: ${existingItem.status} -> ${isActive}")
                    existingItem.status = isActive
                    doUpdate = true
                }
                
                // Update gateway
                if (cloudItem.gateway && existingItem.gateway != cloudItem.gateway) {
                    log.debug("Updating gateway for ${existingItem.name}: ${existingItem.gateway} -> ${cloudItem.gateway}")
                    existingItem.gateway = cloudItem.gateway
                    existingItem.dnsPrimary = cloudItem.gateway
                    doUpdate = true
                }
                
                // Update display name
                def displayName = cloudItem.name ?: cloudItem.iface
                if (existingItem.displayName != displayName) {
                    log.debug("Updating displayName for ${existingItem.name}: ${existingItem.displayName} -> ${displayName}")
                    existingItem.displayName = displayName
                    doUpdate = true
                }
                
                // Update description and CIDR
                if (cloudItem.networkAddress && existingItem.description != cloudItem.networkAddress) {
                    log.debug("Updating networkAddress for ${existingItem.name}: ${existingItem.description} -> ${cloudItem.networkAddress}")
                    existingItem.description = cloudItem.networkAddress
                    existingItem.cidr = cloudItem.networkAddress
                    doUpdate = true
                }
                
                // Update name if it has changed
                if (existingItem.name != cloudItem.iface) {
                    log.debug("Updating name for ${existingItem.name}: ${existingItem.name} -> ${cloudItem.iface}")
                    existingItem.name = cloudItem.iface
                    doUpdate = true
                }
                
                if (doUpdate) {
                    saveList << existingItem
                }
            }
            
            if (saveList) {
                log.debug "Saving ${saveList.size()} updated networks"
                morpheusContext.async.network.save(saveList).blockingGet()
            }
        } catch(e) {
            log.error "Error in updateMatchedNetworks: ${e}", e
        }
    }


    private removeMissingNetworks(List<NetworkIdentityProjection> removeItems) {
        log.debug "removeMissingNetworks: ${cloud.name} ${removeItems?.size()}"
        
        try {
            if (removeItems) {
                log.info("Remove Networks: ${removeItems.size()}")
                morpheusContext.async.network.bulkRemove(removeItems).blockingGet()
            }
        } catch(e) {
            log.error "Error in removeMissingNetworks: ${e}", e
        }
    }
}