import com.jcraft.jsch.*

/**
 * Class to control connections to the reMarkable2 using Java Secure Channel
 */
class SshConnection {

    Properties config = new Properties()
    Session session

    /**
     * Creates JSch Session to reMarkable2.
     *
     * @param username String reMarkable2 username (optional. default = 'root')
     * @param hostname String reMarkable2 IP Address (optional. default = '10.11.99.1')
     * @param port int reMarkable2 SSH port (optional. default = 22)
     */
    SshConnection(String username="root", String hostname="10.11.99.1", int port=22) {
        JSch jsch = new JSch()
        session = jsch.getSession(username, hostname, port)
        config.put('StrictHostKeyChecking', 'no')
        session.setConfig(config)
        session.setPassword(getSessionPasswd())
    }

    /**
     * Connect the JSch Session.
     */
    boolean connect() {
        println 'Attempting to connect to reMarkable2.'
        try {
            session.connect()
            return true
        } catch (JSchException e) {
            println e
            return false
        }
    }

    /**
     * Disconnect the JSch Session.
     */
    boolean disconnect() {
        println 'Disconnecting JSch Session.'
        session.disconnect()
    }

    /**
     * Obtain SSH session password from file or prompts user.
     */
    String getSessionPasswd() {
        String passwd = ''

        // DEV TESTING: gets password from file
        File pwTempFile = new File('./pw')

        if (pwTempFile.exists()) {
            passwd = pwTempFile.readLines().get(0)
        } else {
            def cons = System.console()
            if (cons) {
                passwd = cons.readPassword('Enter your ssh password: ')
            }
        }

        return passwd
    }

    /**
     * Run a simple command via ssh on the reMarkable2.
     *
     * @param command String command to execute remotely
     */
    void runCommand(String command) {
        Channel channel = session.openChannel('exec')
        ((ChannelExec) channel).setCommand(command)
        channel.connect()
        println "Command '" + command + "' sent."
        channel.disconnect()
    }

    /**
     * Run a remote command via SSH and capture the output.
     *
     * Adapted from http://www.jcraft.com/jsch/examples/Exec.java.html
     *
     * @param command String command to execute remotely
     * @return Map [exitStatus: int, output: string]
     */
    def runCommandGetOutput(String command) {
        Channel channel = session.openChannel('exec')
        ((ChannelExec) channel).setCommand(command)
        channel.setInputStream(null)
        ((ChannelExec)channel).setErrStream(System.err)
        InputStream in = channel.getInputStream()
        channel.connect()
        println "Command '" + command + "' sent."

        // Read in the remote commands output
        byte[] buffer = new byte[1024]

        def cmdOutput = ''
        def cmdExitStatus
        while (true) {
            while (in.available() > 0) {
                int i = in.read(buffer, 0, 1024)
                if (i < 0) { break }
                cmdOutput += new String(buffer, 0, i)
            }

            if (channel.isClosed()) {
                if (in.available() > 0) { continue }
                cmdExitStatus = channel.getExitStatus()
                break;
            }

            try {
                Thread.sleep(1000)
            } catch (Exception e) {
                println e
            }
        }

        channel.disconnect()

        def results = [
            exitStatus: cmdExitStatus,
            output: cmdOutput
        ]

        return results
    }

    /**
     * Copy file from reMarkable2 to local working directory.
     *
     * Adapted from https://jcraft.com/jsch/examples/ScpFrom.java.html
     *
     * @param remoteFilename String full path and filename of remote file to transfer.
     * @param storeToLocation String full path with optional filename of local working directory.
     *  If a new filename is not included, it is taken from remoteFilename.
     */
    void scpRemoteToLocal(String remoteFilename, String storeToLocation)
            throws JSchException, IOException {

        // Add remote filename to storage path if it does not exist
        if (new File(storeToLocation).isDirectory()) {
            storeToLocation += File.separator + new File(remoteFilename).getName()
        }

        // Execute 'scp -f <remoteFilename>' on the remote host (reMarkable2).
        // The undocumented '-f' (from) flag tells scp that it is to serve as the server.
        String scpCommand = 'scp -f ' + remoteFilename
        Channel channel = session.openChannel('exec')
        ((ChannelExec) channel).setCommand(scpCommand)

        // Get the IO streams for the remote SCP
        OutputStream out = channel.getOutputStream()
        InputStream in = channel.getInputStream()

        channel.connect()

        byte[] buffer = new byte[1024]

        sendAck(buffer, out)

        // Read in the SCP payload
        while (true) {
            // The scp command is a single letter follwed by arguments and a new-line.
            // SCP 'C' File Transfer syntax: C permissions size filename
            if (checkAck(in) != 'C') { break }

            // Read in the 5 character permissions ('0644 '), but we don't have to do anything with it
            in.read(buffer, 0, 5)

            // Read in the filesize, terminated by a space char
            long filesize = 0L
            while (true) {
                int len = in.read(buffer, 0, 1)

                // length of zero indicates nothing was read (error)
                if (len < 0) { break }

                // space char terminates filesize
                if (buffer[0] == ' ') { break }

                filesize = filesize * 10L + (long) (buffer[0] - (char) '0')
            }

            // Read in the filename, terminated by a null byte ('0x0a')
            String file = null
            for (int i = 0; ; i++) {
                int len = in.read(buffer, i, 1)

                // length of zero indicates nothing was read (error)
                if (len < 0) { break }

                // Check for null byte signifying end of the filename
                if (buffer[i] == (byte) 0x0a) {
                    file = new String(buffer, 0, i)
                    break
                }
            }

            println "Receiving $file of size $filesize"

            sendAck(buffer, out)

            // Read the contents of the remote file and store to local working directory
            FileOutputStream fos = new FileOutputStream(storeToLocation)

            int len
            while (true) {
                if (buffer.length < filesize) {
                    len = buffer.length
                } else {
                    len = (int) filesize
                }
                len = in.read(buffer, 0, len)

                // length of zero indicates nothing was read (error)
                if (len < 0) { break }

                // write to the file until there is no length left
                fos.write(buffer, 0, len)
                filesize -= len
                if (filesize == 0L) { break }
            }

            if (checkAck(in) != 0) { System.exit(0) }

            sendAck(buffer, out)

            try {
                if (fos != null) { fos.close() }
            } catch (Exception e) {
                println e
            }
        }

        channel.disconnect()
    }

    /**
     * Copy file from local to remarkable2.
     *
     * Adapted from: http://www.jcraft.com/jsch/examples/ScpTo.java.html
     *
     * @param localFilename String full path and filename of remote file to transfer.
     * @param storeToLocation String full path with optional filename of remote location.
     *  If new filename is not included, filename is taken from localFilename.
     */
    void scpLocalToRemote(String localFilename, String storeToLocation)
            throws JSchException, IOException {

        // Execute 'scp -t <remoteFilename>' on the remote host.
        // The undocumented -t (to) flag tells scp that it serves as the client.
        String command = 'scp -t ' + storeToLocation
        Channel channel = session.openChannel('exec')
        ((ChannelExec) channel).setCommand(command)

        // get I/O streams for remote scp
        OutputStream out = channel.getOutputStream();
        InputStream in = channel.getInputStream();

        channel.connect();

        // Check for non-error response from remote
        if (checkAck(in) != 0) { System.exit(0) }

        File localFile = new File(localFilename)

        // send "C0644 filesize filename", where filename should not include '/'
        long filesize = localFile.length()
        command = 'C0644 ' + filesize + ' '
        if (localFilename.lastIndexOf('/') > 0) {
            command += localFilename.substring(localFilename.lastIndexOf('/') + 1)
        } else {
            command += localFilename
        }

        command += "\n"
        out.write(command.getBytes())
        out.flush()

        if (checkAck(in) != 0) { System.exit(0) }

        // Send the contents of the local file to the remote reMarkable2
        FileInputStream fis = new FileInputStream(localFilename)
        byte[] buffer = new byte[1024]
        while (true) {
            int len = fis.read(buffer, 0, buffer.length)
            if (len <= 0) { break }
            out.write(buffer, 0, len)
        }

        sendAck(buffer, out)

        if (checkAck(in) != 0) { System.exit(0) }

        out.close()

        try {
            if (fis != null) { fis.close() }
        } catch (Exception ex) {
            println ex
        }

        channel.disconnect()
    }

    /**
     * Checks the SCP Response.
     *
     * Adapted from: http://www.jcraft.com/jsch/examples/ScpTo.java.html
     *
     * @param in InputStream the input stream from SCP in which to read the response
     * @return int the single-byte response
     */
    private static int checkAck(InputStream in) throws IOException {
        // Single byte response from SCP
        //  * 0x00 = ok
        //  * 0x01 = error
        //  * 0x02 = fatal error (OpenSSH never responds with 0x02)
        // Error codes are followed by an error message terminated with a new-line.
        int b = in.read()

        if (b == 0) { return b }

        if (b == 1 || b == 2) {
            StringBuffer sb = new StringBuffer()

            // Read error message from input stream and output to stdout
            int c
            do {
                c = in.read()
                sb.append((char)c)
            } while ( c != '\n')

            println sb.toString()
        }

        return b
    }

    /**
     * Sends an acknoledgement/confirmation byte to the remote.
     *
     * NULL '\0' char is confirmation/response to the remote scp
     *
     * @param buffer byte buffer for sending data to remote
     * @param out OutputStream the stream in which to send the data
     */
    private void sendAck(byte[] buffer, OutputStream out) {
        buffer[0] = 0
        out.write(buffer, 0, 1)
        out.flush()
    }

}
