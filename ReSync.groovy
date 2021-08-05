import SshConnection

class ReSync {
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
    }

    void disconnect() {
        sshConn.disconnect()
    }

    /**
     * Creates gzipped tarball of a target directory or files for backing up
     * 
     * @param archive String base filename of gzipped tarball to create
     * @param target String path of files to backup
     */
    void createBackup(String archive, String target) {
        String fullArchive = archive + "_" + timestamp + ".tar.gz"
        String command = "tar -zcvf " + fullArchive + " " + target
        sshConn.runCommand(command)
    }

    /**
     * Obtains current date/time
     */
    private String createTimestampForSession() {
        return new Date().format("YYMMdd-HHmm")
    }

    static void main(String[] args) {
        ReSync reSync = new ReSync()

        reSync.createBackup("templates", "/usr/share/remarkable/templates")
        reSync.createBackup("images", "/usr/share/remarkable/*.png")

        reSync.disconnect()
    }

}




