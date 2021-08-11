import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * ReSync will perform the following actions on a reMarkable2 Tablet:
 *
 * 1. Backup templates and images
 * 2. Copy custom images to reMarkable2
 * 3. Update the templates Json file with custom templates and removed templates
 * 4. Reboot the reMarkable2
 */
class ReSync {

    static final String WORK_DIR_PREFIX = './work_'

    static final String RM_HOME_DIR = './'
    static final String RM_ROOT_DIR = '/usr/share/remarkable/'
    static final String RM_TEMPLATE_DIR = RM_ROOT_DIR + 'templates/'


    SshConnection sshConn
    String TIMESTAMP
    String WORKING_DIR

    ReSync() {
        sshConn = new SshConnection()
        TIMESTAMP = createTimestampForSession()

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
        createWorkingDir()
        backupReMarkableFiles()
        copyImagesToReMarkable()
        copyTemplatesToReMarkable()
        updateTemplates()

        /* TODO
         * - update JSON with new templates
         * - copy templates to reMarkable
         * - copy JSON to reMarkable
         * - reboot reMarkable
         */
    }

    void createWorkingDir() {
        WORKING_DIR = WORK_DIR_PREFIX + TIMESTAMP + '/'
        new File(WORKING_DIR).mkdirs()
    }

    void backupReMarkableFiles() {
        // Create backups on reMarkable2
        String templatesBackupFile = createBackupTarGz('templates', RM_TEMPLATE_DIR)
        String imagesBackupFile = createBackupTarGz('images', RM_ROOT_DIR + '*.png')

        // Transfer backups to local
        sshConn.scpRemoteToLocal(templatesBackupFile, WORKING_DIR)
        sshConn.scpRemoteToLocal(imagesBackupFile, WORKING_DIR)
    }

    void copyImagesToReMarkable() {
        File imagesDir = new File('./images/')

        imagesDir.eachFile { imageFile ->
            println 'Transferring ' + imageFile
            sshConn.scpLocalToRemote(imageFile.toString(), RM_ROOT_DIR)
        }
    }

    void copyTemplatesToReMarkable() {
        File templatesDir = new File('./templates/')

        templatesDir.eachFile { templateFile ->
            println 'Transferring ' + templateFile
            sshConn.scpLocalToRemote(templateFile.toString(), RM_TEMPLATE_DIR)
        }
    }

    void updateTemplates() {
        fetchTemplateJson()

        File origJsonTemplates = new File(WORKING_DIR + 'templates.orig.json')
        def jsonSlurper = new JsonSlurper()
        def jsonData = jsonSlurper.parse(origJsonTemplates)

        // Remove templates that need to be excluded
        def templatesToExclude = []
        new File('excludes.txt').eachLine { templateFilename ->
            templatesToExclude << templateFilename
        }
        jsonData.templates.removeAll {
            it.filename in templatesToExclude
        }

        // TODO Add custom templates

        def jsonOutStr = JsonOutput.toJson(jsonData)
        def jsonBeauty = JsonOutput.prettyPrint(jsonOutStr)
        File newJsonTemplates = new File(WORKING_DIR + 'templates.json')
        newJsonTemplates.write(jsonBeauty)
    }

    void fetchTemplateJson() {
        sshConn.scpRemoteToLocal(RM_TEMPLATE_DIR + 'templates.json', WORKING_DIR + 'templates.orig.json')
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
        String fullArchive = archive + '_' + TIMESTAMP + '.tar.gz'
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
     * @return String date/TIMESTAMP
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
