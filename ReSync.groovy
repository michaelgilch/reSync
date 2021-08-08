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

    static final String BACKUP_DIR = './backups/'

    SshConnection sshConn
    String timestamp

    ReSync() {
        sshConn = new SshConnection()
        timestamp = createTimestampForSession()

        if (sshConn.connect()) {
            println 'Connected.'
        } else {
            println 'Error connecting to reMarkable2. Exiting'
            System.exit(1)
        }

        File backupDir = new File(BACKUP_DIR)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
    }

    void disconnect() {
        sshConn.disconnect()
    }

    void performSync() {
        // backupReMarkableFiles()
        // copyImages()

        updateTemplates()
        /* TODO
         * - fetch templates JSON
         * - update JSON with removed templates
         * - update JSON with new templates
         * - copy templates to reMarkable
         * - copy JSON to reMarkable
         * - reboot reMarkable
         */
    }

    void backupReMarkableFiles() {
        // Create backups on reMarkable2
        String templatesBackupFile = createBackupTarGz('templates', '/usr/share/remarkable/templates')
        String imagesBackupFile = createBackupTarGz('images', '/usr/share/remarkable/*.png')

        // Transfer backups to local
        sshConn.scpRemoteToLocal(templatesBackupFile, BACKUP_DIR)
        sshConn.scpRemoteToLocal(imagesBackupFile, BACKUP_DIR)
    }

    void copyImages() {
        File imagesDir = new File('./images/')

        imagesDir.eachFile { imageFile ->
            println 'Transferring ' + imageFile
            sshConn.scpLocalToRemote(imageFile.toString(), '/usr/share/remarkable/')
        }
    }

    void updateTemplates() {
        fetchTemplateJson()

        File origJsonTemplates = new File('./templates.orig.json')
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
        File newJsonTemplates = new File('./templates.json')
        newJsonTemplates.write(jsonBeauty)
    }

    void fetchTemplateJson() {
        sshConn.scpRemoteToLocal('/usr/share/remarkable/templates/templates.json', './templates.orig.json')
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
