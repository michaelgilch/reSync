import SshConnection

class ReSync {
    SshConnection sshConn    

    ReSync() {
        sshConn = new SshConnection()

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
     * @param archive String filename of gzipped tarball to create
     * @param target String path of files to backup
     */
    void createBackup(String archive, String target) {
        String command = "tar -zcvf " + archive + " " + target
        sshConn.runCommand(command)
    }


    static void main(String[] args) {
        ReSync reSync = new ReSync()

        reSync.createBackup("templates.tar.gz", "/usr/share/remarkable/templates")
        reSync.createBackup("images.tar.gz", "/usr/share/remarkable/*.png")

        reSync.disconnect()
    }

}




