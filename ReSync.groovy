import SshConnection

class ReSync {

    final String BACKUP_DIR = "./backups/"

    SshConnection sshConn
    String timestamp 

    ReSync() {
        sshConn = new SshConnection()
        timestamp = createTimestampForSession()

        if (sshConn.connect()) {
            println "Connected."
        } else {
            println "Error connecting to reMarkable2. Exiting"
            System.exit(1)        
        }

        File backup_dir = new File(BACKUP_DIR)
        if (!backup_dir.exists()) { 
            backup_dir.mkdirs()
        }
    }

    void disconnect() {
        sshConn.disconnect()
    }

    void performSync() {

        backupReMarkableFiles()

    }

    void backupReMarkableFiles() {
        // Create backups on reMarkable2
        String templatesBackupFile = createBackupTarGz("templates", "/usr/share/remarkable/templates")
        String imagesBackupFile = createBackupTarGz("images", "/usr/share/remarkable/*.png")

        sleep(5000)

        // Transfer backups to local
        sshConn.scpRemoteToLocal(templatesBackupFile, BACKUP_DIR)
        sshConn.scpRemoteToLocal(imagesBackupFile, BACKUP_DIR)
    }

    /**
     * Creates gzipped tarball of a target directory or files for backing up
     * 
     * @param archive String base filename of gzipped tarball to create
     * @param target String path of files to backup
     *
     * @return String filename of gzipped tarball created
     */
    String createBackupTarGz(String archive, String target) {
        String fullArchive = archive + "_" + timestamp + ".tar.gz"
        String command = "tar -zcvf " + fullArchive + " " + target
        sshConn.runCommand(command)

        return fullArchive
    }

    /**
     * Obtains current date/time
     * 
     * @return String date/timestamp
     */
    private String createTimestampForSession() {
        return new Date().format("YYMMdd-HHmm")
    }

    static void main(String[] args) {
        ReSync reSync = new ReSync()
        reSync.performSync()
        reSync.disconnect()
    }

}




