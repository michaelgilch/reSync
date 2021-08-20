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
    static final String TEMPLATES_JSON_FILENAME = 'templates.json'

    static final String RM_HOME_DIR = './'
    static final String RM_ROOT_DIR = '/usr/share/remarkable/'
    static final String RM_TEMPLATE_DIR = RM_ROOT_DIR + 'templates/'
    static final String RM_TEMPLATES_JSON_FILENAME = RM_TEMPLATE_DIR + TEMPLATES_JSON_FILENAME

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
        String newJsonTemplatesFile = workDir + TEMPLATES_JSON_FILENAME
        sshConn.scpLocalToRemote(newJsonTemplatesFile, RM_TEMPLATE_DIR)
    }

    void updateTemplates() {
        fetchTemplatesJsonFile()

        def origJsonData = extractJsonFromFile(new File(workDir + TEMPLATES_JSON_FILENAME + '.orig'))

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
        def newJsonData = extractJsonFromFile(new File('custom_' + TEMPLATES_JSON_FILENAME))
        newJsonData.templates.each { newJson ->
            templates << newJson
        }

        // Convert back to JSON
        def newJsonFileData = ["templates":templates]
        def jsonOutput = JsonOutput.toJson(newJsonFileData)
        def prettyJsonOutput = JsonOutput.prettyPrint(jsonOutput)
        File newJsonTemplatesFile = new File(workDir + TEMPLATES_JSON_FILENAME)
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
        sshConn.scpRemoteToLocal(RM_TEMPLATES_JSON_FILENAME, workDir + + TEMPLATES_JSON_FILENAME + '.orig')
    }

    /**
     * Creates gzipped tarball of a target directory or files
     *
     * @param archive String base filename of gzipped tarball to create
     * @param target String path of files to backup
     *
     * @return String filename of gzipped tarball created
     */
    String createBackupTarGz(String archive, String target) {
        String fullArchiveFilename = getArchiveFilename(archive)

        sshConn.runCommand(getTarGzCommand(fullArchiveFilename, target))
        waitForStableFileSize(fullArchiveFilename)

        return fullArchiveFilename
    }

    /**
     * Constructs a full filename for an archive based on the archive name and timestamp
     *
     * @param String base name for archive
     * @return String filename including timestamp and extensions
     */
    String getArchiveFilename(String basename) {
        return basename + '_' + timestamp + '.tar.gz'
    }

    /**
     * Constructs a tar gz command to create the archive based on the archive name and target
     *
     * @param String archiveFilename filename of archive to create
     * @param String target filepath of archive contents
     * @return String command to run to produce archive
     */
    String getTarGzCommand(String archiveFilename, String target) {
        return 'tar -zcvf ' + archiveFilename + ' ' + target
    }

    /**
     * Waits until a filesize is stable (no longer growing or shrinking)
     *
     * @param String filePath location of file
     */
    void waitForStableFileSize(String filePath) {
        println 'Waiting for stable filesize for ' + filePath
        int lastFileSize = -1
        int fileSize = getRemoteFileSize(filePath)
        while (fileSize != lastFileSize) {
            sleep(250)
            lastFileSize = fileSize
            fileSize = getRemoteFileSize(filePath)
        }
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
            println 'Size of ' + filePath ' = ' + fileSize
        }
        return fileSize
    }

    /**
     * Obtains current date/time
     *
     * @return String date/timestamp
     */
    String createTimestampForSession() {
        return new Date().format('yyMMdd-HHmm')
    }

    static void main(String[] args) {
        ReSync reSync = new ReSync()
        reSync.performSync()
        reSync.disconnect()
    }

}
