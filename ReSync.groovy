import com.jcraft.jsch.*

final String RE_USER = "root"
final String RE_HOST = "10.11.99.1"
final int RE_PORT = 22

String getSessionPasswd() {
    String passwd = ''

    def cons = System.console()
    if (cons) {
        passwd = cons.readPassword('Enter your ssh password: ')
    }
    return passwd
}

JSch jsch = new JSch()
Session session = jsch.getSession(RE_USER, RE_HOST, RE_PORT)
Properties config = new Properties()
config.put("StrictHostKeyChecking", "no")
session.setConfig(config)
session.setPassword(getSessionPasswd())
session.connect()
println "connected"
session.disconnect()

