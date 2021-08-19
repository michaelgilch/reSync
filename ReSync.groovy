import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * ReSync will perform the following actions on a reMarkable2 Tablet:
 *
 * 1. Backup templates and images
 * 2. Copy custom templates and images to reMarkable2
 * 3. Update the templates Json file with custom templates and removed templates
 * 4. Reboot the reMarkable2
 */
class ReSync {

    static final String WORK_DIR_PARENT = './work/'

    static final String CUSTOM_TEMPLATES_DIR = './templates/'
    static final String CUSTOM_IMAGES_DIR = './images/'

    static final String RM_HOME_DIR = './'
    static final String RM_ROOT_DIR = '/usr/share/remarkable/'
    static final String RM_TEMPLATE_DIR = RM_ROOT_DIR + 'templates/'

    SshConnection sshConn
    String timestamp
    String workDir

    ReSync() {
        sshConn = new SshConnection()
        timestamp = createTimestampForSession()

        if (sshConn.connect()) {
            println 'Connected.'
        } else {
            println 'Error connecting to reMarkable2. Exiting'
            System.exit(1)
        }
    }

    void disconnect() {
        sshConn.disconnect()
    }

    void performSync() {
        makeWorkDirectory()
        backupReMarkableFiles()
        updateTemplates()
        copyImagesToReMarkable()
        copyTemplatesToReMarkable()
        copyJsonToReMarkable()
        rebootReMarkable()

        println 'reMarkable reSync complete!'
    }

    /**
     * Creates a directory in the local filesystem to store files used during the session.
     */
    void makeWorkDirectory() {
        workDir = WORK_DIR_PARENT + timestamp + '/'
        new File(workDir).mkdirs()
    }

    /**
     * Facilitates the creation and transfer of reMarkable backups.
     */
    void backupReMarkableFiles() {
        // Create backups on reMarkable2
        String templatesBackupFile = createBackupTarGz('templates', RM_TEMPLATE_DIR)
        String imagesBackupFile = createBackupTarGz('images', RM_ROOT_DIR + '*.png')

        // Transfer backups to local
        sshConn.scpRemoteToLocal(templatesBackupFile, workDir)
        sshConn.scpRemoteToLocal(imagesBackupFile, workDir)
    }

    /**
     * Copies the contents of a local directory to a reMarkable directory, using SCP.
     * 
     * @param String localDirectory path of local directory to copy from
     * @param String remarkableDirectory path of remarkable directory to copy to
     */
    void copyDirectoryContentsToRemarkable(String localDirectory, String remarkableDirectory) {
        File directory = new File(localDirectory)
        directory.eachFile { file ->
            println 'Transferring ' + file
            sshConn.scpLocalToRemote(file.toString(), remarkableDirectory)
        }
    }

    void copyImagesToReMarkable() {
        copyDirectoryContentsToRemarkable(CUSTOM_IMAGES_DIR, RM_ROOT_DIR)
    }

    void copyTemplatesToReMarkable() {
        copyDirectoryContentsToRemarkable(CUSTOM_TEMPLATES_DIR, RM_TEMPLATE_DIR)
    }

    void copyJsonToReMarkable() {
        String newJsonTemplatesFile = workDir + 'templates.json'
        sshConn.scpLocalToRemote(newJsonTemplatesFile, RM_TEMPLATE_DIR)
    }

    void updateTemplates() {
        fetchTemplatesJsonFile()

        def origJsonData = extractJsonFromFile(new File(workDir + 'templates.orig.json'))

        // Remove templates that need to be excluded
        def templatesToExclude = []
        new File('excludes.txt').eachLine { templateFilename ->
            templatesToExclude << templateFilename
        }
        origJsonData.templates.removeAll {
            it.filename in templatesToExclude
        }

        // Convert templates to lists
        def templates = []
        origJsonData.templates.each { template ->
            templates << template
        }

        // Add custom templates
        def newJsonData = extractJsonFromFile(new File('custom_templates.json'))
        newJsonData.templates.each { newJson ->
            templates << newJson
        }

        // Convert back to JSON
        def newJsonFileData = ["templates":templates]
        def jsonOutput = JsonOutput.toJson(newJsonFileData)
        def prettyJsonOutput = JsonOutput.prettyPrint(jsonOutput)
        File newJsonTemplatesFile = new File(workDir + 'templates.json')
        newJsonTemplatesFile.write(prettyJsonOutput)
    }

    def extractJsonFromFile(File jsonFile) {
        def jsonSlurper = new JsonSlurper()
        def jsonData = jsonSlurper.parse(jsonFile)
        return jsonData
    }

    void rebootReMarkable() {
        println 'Restarting reMarkable xochitl service...'
        sshConn.runCommand('systemctl restart xochitl')
    }

    /**
     * Obtains the templates.json file from the reMarkable2
     */
    void fetchTemplatesJsonFile() {
        sshConn.scpRemoteToLocal(RM_TEMPLATE_DIR + 'templates.json', workDir + 'templates.orig.json')
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
        String fullArchive = archive + '_' + timestamp + '.tar.gz'
        String command = 'tar -zcvf ' + fullArchive + ' ' + target
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
        String command = 'ls -l | grep ' + filePath
        Map results = sshConn.runCommandGetOutput(command)

        long fileSize = 0
        if (results.exitStatus != 0) {
            println 'Could not get fileSize.'
        } else {
            // Example output: [drwxr-xr-x, 3, root, root, 12288, Aug, 2, 09:06, templates.bak]
            fileSize = results.output.split(' +')[4] as Integer
        }
        return fileSize
    }

    /**
     * Obtains current date/time
     *
     * @return String date/timestamp
     */
    private String createTimestampForSession() {
        return new Date().format('YYMMdd-HHmm')
    }

    static void main(String[] args) {
        ReSync reSync = new ReSync()
        reSync.performSync()
        reSync.disconnect()
    }

}
