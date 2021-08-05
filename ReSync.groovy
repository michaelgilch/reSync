import SshConnection

SshConnection sshConn = new SshConnection()

if (sshConn.connect()) {
    println "Connected."
} else {
    println "Error connecting to reMarkable2. Exiting"
    System.exit(1)
}

createBackup(sshConn, "/home/root/templates.tar.gz", "/usr/share/remarkable/templates")
createBackup(sshConn, "/home/root/images.tar.gz", "/usr/share/remarkable/*.png")

sshConn.disconnect()

void createBackup(SshConnection sshConn, String archive, String target) {
    String command = "tar -zcvf " + archive + " " + target
    sshConn.runCommand(command)
}
