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
        copyImages()

    }

    void backupReMarkableFiles() {
        // Create backups on reMarkable2
        String templatesBackupFile = createBackupTarGz("templates", "/usr/share/remarkable/templates")
        String imagesBackupFile = createBackupTarGz("images", "/usr/share/remarkable/*.png")

        // Transfer backups to local
        sshConn.scpRemoteToLocal(templatesBackupFile, BACKUP_DIR)
        sshConn.scpRemoteToLocal(imagesBackupFile, BACKUP_DIR)
    }

    void copyImages() {
        File imagesDir = new File("./images/")

        imagesDir.eachFile { imageFile ->
            println "Transferring " + imageFile.toString()
            sshConn.scpLocalToRemote(imageFile.toString(), "/usr/share/remarkable/")
        }

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

        // To prevent working with incomplete archives, do not return until the size of the archive is stable
        int lastFileSize = -1
        int fileSize = getRemoteFileSize(fullArchive)
        while (fileSize != lastFileSize) {
            lastFileSize = fileSize
            sleep(500)
            fileSize = getRemoteFileSize(fullArchive)
        }

        return fullArchive
    }

    /**
     * Returns the filesize of a remote file.
     *
     * @param filePath String path of file to check size
     * @return int size of file
     */
    int getRemoteFileSize(String filePath) {
        String command = "ls -l | grep " + filePath
        def results = sshConn.runCommandGetOutput(command)

        def fileSize = 0
        if (results.exitStatus != 0) {
            println "Could not get fileSize."
        } else {
            // Example output: [drwxr-xr-x, 3, root, root, 12288, Aug, 2, 09:06, templates.bak]
            fileSize = results.output.split(" +")[4] as Integer
        }
        return fileSize
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




