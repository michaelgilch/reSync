import SshConnection

SshConnection sshConn = new SshConnection()

if (sshConn.connect()) {
    println "Connected."
} else {
    println "Error connecting to reMarkable2. Exiting"
    System.exit(1)
}

createBackups(sshConn)

sshConn.disconnect()

void createBackups(SshConnection sshConn) {
    sshConn.runCommand("touch a")
}