package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupJob
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxBackupProvider extends AbstractBackupProvider {

    public static final String PROVIDER_CODE = 'proxmox-ve-backup'

    protected MorpheusContext morpheusContext
    protected ProxmoxVePlugin plugin

    ProxmoxBackupProvider(ProxmoxVePlugin plugin, MorpheusContext morpheusContext) {
        super()
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return plugin
    }

    @Override
    Collection<OptionType> getOptionTypes() {
        return []
    }

    @Override
    Icon getIcon() {
        return new Icon(path: 'proxmox.svg', darkPath: 'proxmox-dark.svg')
    }

    @Override
    Collection<OptionType> getReplicationGroupOptionTypes() {
        return []
    }

    @Override
    Collection<OptionType> getReplicationOptionTypes() {
        return []
    }

    @Override
    Collection<OptionType> getBackupJobOptionTypes() {
        return []
    }

    @Override
    Collection<OptionType> getBackupOptionTypes() {
        return []
    }

    @Override
    Collection<OptionType> getInstanceReplicationGroupOptionTypes() {
        return []
    }

    @Override
    ServiceResponse deleteBackupProvider(BackupProviderModel backupProvider, Map opts) {
        try {
            log.info("Deleting backup provider: ${backupProvider.name}")
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Failed to delete backup provider: ${e.message}", e)
            return ServiceResponse.error("Failed to delete backup provider: ${e.message}")
        }
    }

    @Override
    ServiceResponse refresh(BackupProviderModel backupProvider) {
        try {
            log.info("Refreshing backup provider: ${backupProvider.name}")
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Failed to refresh backup provider: ${e.message}", e)
            return ServiceResponse.error("Failed to refresh backup provider: ${e.message}")
        }
    }

    @Override
    BackupJobProvider getBackupJobProvider() {
        return null // Return null if no specific backup job provider is needed
    }

    Boolean canBackupServer(ComputeServer server) {
        return true
    }

    @Override
    String getCode() {
        return PROVIDER_CODE
    }

    @Override
    String getName() {
        return 'Proxmox VE Backup'
    }

    String getDescription() {
        return 'Native Proxmox VE backup and snapshot management'
    }

    ServiceResponse configureBackup(BackupJob backupJob, Map config, Map opts) {
        try {
            log.info("Configuring backup job: ${backupJob.name}")
            def validation = validateBackupConfig(config)
            if(!validation.success)
                return validation

            backupJob.setConfigProperty('backupType', config.backupType ?: 'snapshot')
            backupJob.setConfigProperty('retentionCount', config.retentionCount ?: 7)
            backupJob.setConfigProperty('compression', config.compression ?: 'lzo')
            backupJob.setConfigProperty('mode', config.mode ?: 'snapshot')
            backupJob.setConfigProperty('storage', config.storage)
            backupJob.setConfigProperty('mailto', config.mailto)
            return ServiceResponse.success(backupJob)
        } catch(Exception e) {
            log.error("Backup configuration failed: ${e.message}", e)
            return ServiceResponse.error("Backup configuration failed: ${e.message}")
        }
    }

    ServiceResponse executeBackup(BackupJob backupJob, Map opts) {
        try {
            log.info("Executing backup job: ${backupJob.name}")
            def backupResult = new BackupResult(
                backupJob: backupJob,
                status: 'IN_PROGRESS',
                startDate: new Date(),
                backupType: 'proxmox-snapshot'
            )
            def apiClient = new ProxmoxApiClient(morpheusContext, backupJob.computeServer.cloud, plugin)
            def result = performBackup(apiClient, backupJob, backupResult, opts)
            return ServiceResponse.success(result)
        } catch(Exception e) {
            log.error("Backup execution failed: ${e.message}", e)
            return ServiceResponse.error("Backup execution failed: ${e.message}")
        }
    }

    ServiceResponse deleteBackup(Backup backup, Map opts) {
        try {
            log.info("Deleting backup: ${backup.name}")
            def apiClient = new ProxmoxApiClient(morpheusContext, backup.computeServer.cloud, plugin)
            def node = backup.computeServer.parentServer?.externalId
            def vmId = backup.computeServer.externalId
            if(backup.backupType == 'snapshot') {
                def result = apiClient.deleteSnapshot(node, vmId, backup.externalId)
                if(result) {
                    return ServiceResponse.success()
                } else {
                    return ServiceResponse.error('Failed to delete snapshot')
                }
            } else if(backup.backupType == 'vzdump') {
                def result = deleteBackupFile(apiClient, backup)
                if(result)
                    return ServiceResponse.success()
                else
                    return ServiceResponse.error('Failed to delete backup file')
            }
            return ServiceResponse.error("Unknown backup type: ${backup.backupType}")
        } catch(Exception e) {
            log.error("Backup deletion failed: ${e.message}", e)
            return ServiceResponse.error("Backup deletion failed: ${e.message}")
        }
    }

    ServiceResponse restoreBackup(BackupRestore backupRestore, Map opts) {
        try {
            log.info("Restoring backup: ${backupRestore.backup.name}")
            def backup = backupRestore.backup
            def apiClient = new ProxmoxApiClient(morpheusContext, backup.computeServer.cloud, plugin)
            if(backup.backupType == 'snapshot') {
                return restoreFromSnapshot(apiClient, backupRestore, opts)
            } else if(backup.backupType == 'vzdump') {
                return restoreFromBackupFile(apiClient, backupRestore, opts)
            }
            return ServiceResponse.error("Unsupported backup type for restore: ${backup.backupType}")
        } catch(Exception e) {
            log.error("Backup restore failed: ${e.message}", e)
            return ServiceResponse.error("Backup restore failed: ${e.message}")
        }
    }

    private ServiceResponse validateBackupConfig(Map config) {
        if(config?.backupType == 'vzdump' && !config.storage) {
            return ServiceResponse.error('Storage is required for vzdump backups')
        }
        return ServiceResponse.success()
    }

    private def performBackup(apiClient, BackupJob backupJob, BackupResult backupResult, Map opts) {
        def server = backupJob.computeServer
        def node = server.parentServer?.externalId
        def vmId = server.externalId
        def backupType = backupJob.getConfigProperty('backupType')
        switch(backupType) {
            case 'snapshot':
                return createSnapshotBackup(apiClient, backupJob, backupResult, node, vmId)
            case 'vzdump':
                return createVzdumpBackup(apiClient, backupJob, backupResult, node, vmId)
            default:
                throw new RuntimeException("Unknown backup type: ${backupType}")
        }
    }

    private def createSnapshotBackup(apiClient, BackupJob backupJob, BackupResult backupResult, String node, String vmId) {
        def snapName = "morpheus-backup-${System.currentTimeMillis()}"
        def description = "Morpheus backup created on ${new Date()}"
        try {
            def result = apiClient.createSnapshot(node, vmId, snapName, description)
            if(result) {
                backupResult.status = 'COMPLETED'
                backupResult.endDate = new Date()
                backupResult.externalId = snapName
                backupResult.sizeInMb = 0
                cleanupOldSnapshots(apiClient, backupJob, node, vmId)
                return backupResult
            } else {
                backupResult.status = 'FAILED'
                backupResult.endDate = new Date()
                backupResult.errorMessage = 'Failed to create snapshot'
                return backupResult
            }
        } catch(Exception e) {
            backupResult.status = 'FAILED'
            backupResult.endDate = new Date()
            backupResult.errorMessage = e.message
            throw e
        }
    }

    private def createVzdumpBackup(apiClient, BackupJob backupJob, BackupResult backupResult, String node, String vmId) {
        def storage = backupJob.getConfigProperty('storage') ?: 'local'
        def compression = backupJob.getConfigProperty('compression') ?: 'lzo'
        def mode = backupJob.getConfigProperty('mode') ?: 'snapshot'
        try {
            def backupConfig = [
                vmid: vmId,
                storage: storage,
                mode: mode,
                compress: compression,
                remove: 0,
                notes: "Morpheus backup created on ${new Date()}"
            ]
            def result = apiClient.createVzdumpBackup(node, backupConfig)
            if(result) {
                def taskId = result.data
                def backupInfo = monitorBackupTask(apiClient, node, taskId)
                backupResult.status = backupInfo.success ? 'COMPLETED' : 'FAILED'
                backupResult.endDate = new Date()
                backupResult.externalId = backupInfo.backupFile
                backupResult.sizeInMb = backupInfo.sizeInMb ?: 0
                if(!backupInfo.success)
                    backupResult.errorMessage = backupInfo.error
                else
                    cleanupOldBackups(apiClient, backupJob, node, storage)
                return backupResult
            } else {
                backupResult.status = 'FAILED'
                backupResult.endDate = new Date()
                backupResult.errorMessage = 'Failed to start backup'
                return backupResult
            }
        } catch(Exception e) {
            backupResult.status = 'FAILED'
            backupResult.endDate = new Date()
            backupResult.errorMessage = e.message
            throw e
        }
    }

    private def cleanupOldSnapshots(apiClient, BackupJob backupJob, String node, String vmId) {
        def retentionCount = backupJob.getConfigProperty('retentionCount') as Integer ?: 7
        try {
            def snapshots = apiClient.getVmSnapshots(node, vmId)
            def morpheusSnapshots = snapshots.data?.findAll { it.name?.startsWith('morpheus-backup-') }
            if(morpheusSnapshots && morpheusSnapshots.size() > retentionCount) {
                morpheusSnapshots.sort { it.snaptime }
                morpheusSnapshots.take(morpheusSnapshots.size() - retentionCount).each { snap ->
                    log.info("Cleaning up old snapshot: ${snap.name}")
                    apiClient.deleteSnapshot(node, vmId, snap.name)
                }
            }
        } catch(Exception e) {
            log.warn("Failed to cleanup old snapshots: ${e.message}")
        }
    }

    private def cleanupOldBackups(apiClient, BackupJob backupJob, String node, String storage) {
        def retentionCount = backupJob.getConfigProperty('retentionCount') as Integer ?: 7
        try {
            def backups = apiClient.getStorageBackups(node, storage)
            def vmId = backupJob.computeServer.externalId
            def vmBackups = backups.data?.findAll { it.vmid == vmId }
            if(vmBackups && vmBackups.size() > retentionCount) {
                vmBackups.sort { it.ctime }
                vmBackups.take(vmBackups.size() - retentionCount).each { bk ->
                    log.info("Cleaning up old backup: ${bk.volid}")
                    apiClient.deleteBackupFile(node, storage, bk.volid)
                }
            }
        } catch(Exception e) {
            log.warn("Failed to cleanup old backups: ${e.message}")
        }
    }

    private def restoreFromSnapshot(apiClient, BackupRestore backupRestore, Map opts) {
        def backup = backupRestore.backup
        def server = backup.computeServer
        def node = server.parentServer?.externalId
        def vmId = server.externalId
        def snapName = backup.externalId
        try {
            def result = apiClient.rollbackSnapshot(node, vmId, snapName)
            if(result)
                return ServiceResponse.success('VM restored from snapshot successfully')
            else
                return ServiceResponse.error('Failed to restore from snapshot')
        } catch(Exception e) {
            return ServiceResponse.error("Snapshot restore failed: ${e.message}")
        }
    }

    private def restoreFromBackupFile(apiClient, BackupRestore backupRestore, Map opts) {
        def backup = backupRestore.backup
        def targetNode = opts.targetNode ?: backup.computeServer.parentServer?.externalId
        def newVmId = opts.newVmId ?: getNextAvailableVmId(apiClient, targetNode)
        def storage = opts.storage ?: 'local-lvm'
        try {
            def restoreConfig = [
                archive: backup.externalId,
                vmid: newVmId,
                storage: storage,
                unique: opts.unique ?: false
            ]
            def result = apiClient.restoreBackup(targetNode, restoreConfig)
            if(result) {
                def taskId = result.data
                def restoreInfo = monitorRestoreTask(apiClient, targetNode, taskId)
                if(restoreInfo.success) {
                    return ServiceResponse.success("VM restored successfully with ID: ${newVmId}")
                } else {
                    return ServiceResponse.error("Restore failed: ${restoreInfo.error}")
                }
            } else {
                return ServiceResponse.error('Failed to start restore')
            }
        } catch(Exception e) {
            return ServiceResponse.error("Backup restore failed: ${e.message}")
        }
    }

    private boolean deleteBackupFile(apiClient, Backup backup) {
        def node = backup.computeServer.parentServer?.externalId
        def storage = backup.getConfigProperty('storage') ?: 'local'
        try {
            apiClient.deleteBackupFile(node, storage, backup.externalId)
            return true
        } catch(Exception e) {
            log.warn("Failed to delete backup file: ${e.message}")
            return false
        }
    }

    private def monitorBackupTask(apiClient, String node, String taskId) {
        int attempts = 0
        while(attempts < 60) {
            try {
                def status = apiClient.getTaskStatus(node, taskId)
                if(status.data?.status == 'stopped') {
                    def exitStatus = status.data?.exitstatus
                    return [success: exitStatus == 'OK', backupFile: status.data?.id, sizeInMb: null, error: exitStatus]
                }
                Thread.sleep(5000)
                attempts++
            } catch(Exception e) {
                log.warn("Error monitoring backup task: ${e.message}")
                attempts++
            }
        }
        return [success:false, error:'timeout']
    }

    private def monitorRestoreTask(apiClient, String node, String taskId) {
        int attempts = 0
        while(attempts < 60) {
            try {
                def status = apiClient.getTaskStatus(node, taskId)
                if(status.data?.status == 'stopped') {
                    def exitStatus = status.data?.exitstatus
                    return [success: exitStatus == 'OK', error: exitStatus]
                }
                Thread.sleep(5000)
                attempts++
            } catch(Exception e) {
                log.warn("Error monitoring restore task: ${e.message}")
                attempts++
            }
        }
        return [success:false, error:'timeout']
    }

    private def getNextAvailableVmId(apiClient, String node) {
        try {
            def result = apiClient.getNextVmId(node)
            return result.data ?: result
        } catch(Exception e) {
            log.warn("Failed to get next VM ID: ${e.message}")
            return System.currentTimeMillis() % 100000
        }
    }
}