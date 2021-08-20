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
     * Constructs a full filename for an archive based on the archive name and timestamp.
     *
     * @param basename the base of the archive filename to create, without timestamp or extension 
     * @return the full filename of archive file to create, with timestamp and extension
     */
    String getArchiveFilename(String basename) {
        return "${basename}_${timestamp}.tar.gz"
    }

    /**
     * Constructs a tar gz command to create the archive based on the archive name and target.
     *
     * @param archiveFilename filename of archive to create
     * @param target filepath of archive contents
     * @return command to run to produce the archive
     */
    String getTarGzCommand(String archiveFilename, String target) {
        return "tar -zcvf ${archiveFilename} ${target}"
    }

    /**
     * Waits until a filesize is stable (no longer growing).
     *
     * @param filepath full path and filename of remote file to monitor
     */
    void waitForStableFileSize(String filePath) {
        int sleepTimeBetweenFilesizeChecksInMillis = 250       
        int fileSize = getRemoteFileSize(filePath)
        int lastFileSize = -1

        println "Waiting for stable filesize for ${filePath}"
        
        while (fileSize != lastFileSize) {
            sleep(sleepTimeBetweenFilesizeChecksInMillis)

            lastFileSize = fileSize
            fileSize = getRemoteFileSize(filePath)
        }
    }

    /**
     * Fetches the filesize of a remote file.
     *
     * @param filePath full path and filename of the file to size check
     * @return filesize in bytes
     */
    int getRemoteFileSize(String filePath) {
        Map results = sshConn.runCommandGetOutput(getLsCommand(filePath))

        long fileSize = 0
        if (results.exitStatus != 0) {
            println 'Error: Command failed.'
        } else {
            // Example output: [drwxr-xr-x, 3, root, root, 12288, Aug, 2, 09:06, templates.bak]
            fileSize = results.output.split(' +')[4] as Integer
            println 'Size of ' + filePath ' = ' + fileSize
        }
        return fileSize
    }

    /**
     * Constructs an `ls` command to get file attributes, including the size in bytes.
     *
     * @param filename full path and filename of file to use in construction of command
     * @return command to run to produce the `ls` results
     */
    String getLsCommand(String filename) {
        return "ls --format=list ${filename}"
    }

    /**
     * Obtains current date/time.
     *
     * @return string representation of current date/time
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
