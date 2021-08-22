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

    static final String CUSTOM_TEMPLATES_DIR = './templates/'
    static final String CUSTOM_IMAGES_DIR = './images/'
    static final String WORK_DIR_PARENT = './work/'

    static final String ORIG_FILE_EXTENSION = '.orig'
    static final String TEMPLATES_JSON_FILENAME = 'templates.json'
    static final String TEMPLATES_TO_EXCLUDE_FILENAME = 'exclude.txt'

    static final String RM_HOME_DIR = './'
    static final String RM_ROOT_DIR = '/usr/share/remarkable/'
    static final String RM_TEMPLATE_DIR = RM_ROOT_DIR + 'templates/'
    static final String RM_TEMPLATES_JSON_FILENAME = RM_TEMPLATE_DIR + TEMPLATES_JSON_FILENAME

    SshConnection sshConn
    String timestamp
    String workDir

    static void main(String[] args) {
        ReSync reSync = new ReSync()
        reSync.connect()
        reSync.performSync()
        reSync.disconnect()
    }

    /**
     * Constructor to initialize class variables
     */
    ReSync() {
        sshConn = new SshConnection()
        timestamp = createSessionTimestamp()
        makeWorkDirectory()
    }

    /**
     * Connects to the reMarkable tablet through an SSH session.
     */
    void connect() {
        if (sshConn.connect()) {
            println 'Connected.'
        } else {
            println 'Error connecting to reMarkable2. Exiting'
            System.exit(1)
        }
    }

    /**
     * Disconnects a reMarkable SSH session.
     */
    void disconnect() {
        sshConn.disconnect()
    }

    void performSync() {
        backupReMarkableFiles()
        updateTemplates()
        copyFilesToReMarkable()
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

    void copyFilesToReMarkable() {
        copyImagesToReMarkable()
        copyTemplatesToReMarkable()
        copyJsonToReMarkable()
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
        Map origJsonData = extractJsonFromFile(new File(workDir + TEMPLATES_JSON_FILENAME + ORIG_FILE_EXTENSION))
        Map jsonData = removeUnusedTemplatesFromJson(origJsonData)

        // Convert templates to lists
        List templates = []
        jsonData.templates.each { template ->
            templates << template
        }

        // Add custom templates
        Map newJsonData = extractJsonFromFile(new File('custom_' + TEMPLATES_JSON_FILENAME))
        newJsonData.templates.each { newJson ->
            templates << newJson
        }

        convertTemplatesListToJsonFile(templates)
    }

    void convertTemplatesListToJsonFile(List templates) {
        Map jsonData = ['templates':templates]
        String jsonOutput = JsonOutput.toJson(jsonData)
        String prettyJsonOutput = JsonOutput.prettyPrint(jsonOutput)
        File newJsonTemplateFile = new File(workDir + TEMPLATES_JSON_FILENAME)
        newJsonTemplateFile.write(prettyJsonOutput)
    }

    Map extractJsonFromFile(File jsonFile) {
        JsonSlurper jsonSlurper = new JsonSlurper()
        Map jsonData = jsonSlurper.parse(jsonFile)
        return jsonData
    }

    Map removeUnusedTemplatesFromJson(Map origJsonData) {
        List templatesToExclude = getListOfTemplatesToExclude()

        origJsonData.templates.removeAll { originalTemplate ->
            originalTemplate.filename in templatesToExclude
        }

        return origJsonData
    }

    List getListOfTemplatesToExclude() {
        List templatesToExclude = []
        new File(TEMPLATES_TO_EXCLUDE_FILENAME).eachLine { templateFilename ->
            templatesToExclude << templateFilename
        }
        return templatesToExclude
    }

    void rebootReMarkable() {
        println 'Restarting reMarkable xochitl service...'
        sshConn.runCommand('systemctl restart xochitl')
    }

    /**
     * Obtains the templates.json file from the reMarkable2
     */
    void fetchTemplatesJsonFile() {
        String localFilename = workDir + TEMPLATES_JSON_FILENAME + ORIG_FILE_EXTENSION
        sshConn.scpRemoteToLocal(RM_TEMPLATES_JSON_FILENAME, localFilename)
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
            println 'Size of ' + filePath + ' = ' + fileSize
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
        return "ls -l ${filename}"
    }

    /**
     * Obtains current date/time.
     *
     * @return string representation of current date/time
     */
    String createSessionTimestamp() {
        return new Date().format('yyMMdd-HHmm')
    }

}
