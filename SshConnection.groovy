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
        config.put("StrictHostKeyChecking", "no")
        session.setConfig(config)
        session.setPassword(getSessionPasswd())
    }

    /**
     * Connect the JSch Session.
     */
    boolean connect() {
        println "Attempting to connect to reMarkable2."
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
        println "Disconnecting JSch Session."
        session.disconnect()
    }

    /**
     * Obtain SSH session password from file or prompts user.
     */
    String getSessionPasswd() {
        String passwd = ''

        // DEV TESTING: gets password from file
        File pwTempFile = new File("./pw")

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
}




