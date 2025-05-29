package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j


@Slf4j
class HostSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private Map authConfig


    HostSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@morpheusContext = proxmoxVePlugin.morpheus
        this.@apiClient = apiClient
        this.@authConfig = plugin.getAuthConfig(cloud)
    }


    def execute() {
        log.debug "Execute HostSync STARTED: ${cloud.id}"

        try {
            def hostListResults = ProxmoxApiComputeUtil.listProxmoxHypervisorHosts(apiClient, authConfig)
            log.debug("Host list results: $hostListResults")

            if (hostListResults.success) {
                def cloudItems = hostListResults?.data

                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                    ComputeServerIdentityProjection projection ->
                    if (projection.category == "proxmox.ve.host.${cloud.id}") {
                        return true
                    }
                    false
                }

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem?.node
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: updateItemMap[server.id].masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    addMissingHosts(cloud, itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedHosts(cloud, updateItems)
                }.onDelete { removeItems ->
                    removeMissingHosts(cloud, removeItems)
                }.start()
            } else {
                log.error "Error in getting hosts : ${hostListResults}"
            }
        } catch(e) {
            log.error "Error in HostSync execute : ${e}", e
        }
        log.debug "Execute HostSync COMPLETED: ${cloud.id}"
    }


    private addMissingHosts(Cloud cloud, Collection addList) {
        log.debug "addMissingHosts: ${cloud.name} ${addList?.size()}"
        
        try {
            def serverType = new ComputeServerType(code: 'proxmox-ve-node')
            def serverOs = new OsType(code: 'linux')
            def serversToAdd = []
            def cloudItemList = []

            // Collect all servers first
            for(cloudItem in addList) {
                log.debug("Preparing cloud host: $cloudItem")
                def serverConfig = [
                        account          : cloud.owner,
                        category         : "proxmox.ve.host.${cloud.id}",
                        cloud            : cloud,
                        name             : cloudItem.node,
                        resourcePool     : null,
                        externalId       : cloudItem.node,
                        uniqueId         : "${cloud.id}.${cloudItem.node}",
                        sshUsername      : 'root',
                        status           : 'provisioned',
                        provision        : false,
                        serverType       : 'hypervisor',
                        computeServerType: serverType,
                        serverOs         : serverOs,
                        osType           : 'linux',
                        hostname         : cloudItem.node,
                        externalIp       : cloudItem.ipAddress
                ]

                ComputeServer newServer = new ComputeServer(serverConfig)
                serversToAdd << newServer
                cloudItemList << cloudItem
            }
            
            // Batch create all servers
            if (serversToAdd) {
                log.debug("Creating ${serversToAdd.size()} host servers in batch")
                def createdServers = morpheusContext.async.computeServer.bulkCreate(serversToAdd).blockingGet()
                
                if (createdServers) {
                    log.debug("Successfully created ${serversToAdd.size()} host servers")
                    
                    // Update metrics for each server after creation
                    serversToAdd.eachWithIndex { server, index ->
                        def cloudItem = cloudItemList[index]
                        updateMachineMetrics(
                                server,
                                cloudItem.maxcpu?.toLong(),
                                cloudItem.maxdisk?.toLong(),
                                cloudItem.disk?.toLong(),
                                cloudItem.maxmem?.toLong(),
                                cloudItem.mem?.toLong(),
                                cloudItem.maxcpu?.toLong(),
                                (cloudItem.status == 'online') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                        )
                    }
                } else {
                    log.error "Error in bulk creating host servers"
                }
            }
        } catch(e) {
            log.error "Error in addMissingHosts: ${e}", e
        }
    }


    private updateMatchedHosts(Cloud cloud, List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
        log.debug("Updating ${updateItems?.size()} Hosts...")

        try {
            def saveList = []
            
            for(def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def doUpdate = false

                // Update hostname
                if (cloudItem.node && cloudItem.node != existingItem.hostname) {
                    log.debug("Updating hostname for ${existingItem.name}: ${existingItem.hostname} -> ${cloudItem.node}")
                    existingItem.hostname = cloudItem.node
                    doUpdate = true
                }

                // Update external IP
                if (cloudItem.ipAddress && cloudItem.ipAddress != existingItem.externalIp) {
                    log.debug("Updating externalIp for ${existingItem.name}: ${existingItem.externalIp} -> ${cloudItem.ipAddress}")
                    existingItem.setExternalIp(cloudItem.ipAddress)
                    doUpdate = true
                }

                // Update name if different from node
                if (cloudItem.node && cloudItem.node != existingItem.name) {
                    log.debug("Updating name for ${existingItem.name}: ${existingItem.name} -> ${cloudItem.node}")
                    existingItem.name = cloudItem.node
                    doUpdate = true
                }

                if (doUpdate) {
                    saveList << existingItem
                }

                // Always update machine metrics
                updateMachineMetrics(
                        existingItem,
                        cloudItem.maxcpu?.toLong(),
                        cloudItem.maxdisk?.toLong(),
                        cloudItem.disk?.toLong(),
                        cloudItem.maxmem?.toLong(),
                        cloudItem.mem?.toLong(),
                        cloudItem.maxcpu?.toLong(),
                        (cloudItem.status == 'online') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                )
            }
            
            if (saveList) {
                log.debug "Saving ${saveList.size()} updated hosts"
                morpheusContext.async.computeServer.bulkSave(saveList).blockingGet()
            }
        } catch(e) {
            log.error "Error in updateMatchedHosts: ${e}", e
        }
    }


    private removeMissingHosts(Cloud cloud, List<ComputeServerIdentityProjection> removeList) {
        log.debug "removeMissingHosts: ${cloud.name} ${removeList?.size()}"
        
        try {
            if (removeList) {
                log.info("Remove Hosts: ${removeList.size()}")
                morpheusContext.async.computeServer.bulkRemove(removeList).blockingGet()
            }
        } catch(e) {
            log.error "Error in removeMissingHosts: ${e}", e
        }
    }


    private updateMachineMetrics(ComputeServer server, Long maxCores, Long maxStorage, Long usedStorage, Long maxMemory, Long usedMemory, Long maxCpu, ComputeServer.PowerState status) {
        log.debug "updateMachineMetrics for ${server?.name}"
        
        try {
            def updates = !server.getComputeCapacityInfo()
            ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

            if(capacityInfo.maxCores != maxCores || server.maxCores != maxCores) {
                capacityInfo.maxCores = maxCores
                server?.maxCores = maxCores
                updates = true
            }

            if(capacityInfo.maxStorage != maxStorage || server.maxStorage != maxStorage) {
                capacityInfo.maxStorage = maxStorage
                server?.maxStorage = maxStorage
                updates = true
            }

            if(capacityInfo.usedStorage != usedStorage || server.usedStorage != usedStorage) {
                capacityInfo.usedStorage = usedStorage
                server?.usedStorage = usedStorage
                updates = true
            }

            if(capacityInfo.maxMemory != maxMemory || server.maxMemory != maxMemory) {
                capacityInfo?.maxMemory = maxMemory
                server?.maxMemory = maxMemory
                updates = true
            }

            if(capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
                capacityInfo?.usedMemory = usedMemory
                server?.usedMemory = usedMemory
                updates = true
            }

            if(capacityInfo.maxCpu != maxCpu || server.usedCpu != maxCpu) {
                capacityInfo?.maxCpu = maxCpu
                server?.usedCpu = maxCpu
                updates = true
            }

            // Determine power state based on status
            def powerState = (status == ComputeServer.PowerState.on) ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
            if(server.powerState != powerState) {
                server.powerState = powerState
                updates = true
            }

            if(updates == true) {
                server.capacityInfo = capacityInfo
                morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
            }
        } catch(e) {
            log.warn("error updating host stats for ${server?.name}: ${e}", e)
        }
    }
}