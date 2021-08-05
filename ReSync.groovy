import SshConnection

SshConnection sshConn = new SshConnection()

if (sshConn.connect()) {
    println "Connected."
} else {
    println "Error connecting to reMarkable2. Exiting"
    System.exit(1)
}

sshConn.disconnect()