import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import parser.QuestionParser
import ut.isep.AssessmentParser
import ut.isep.AssessmentUpdater
import java.io.File
import java.io.FileInputStream
import kotlin.system.exitProcess


const val USAGE = """
Usage:
  validate [--all] [<file>...]
  upload [--deleted <file>...] [--added <file>...] [--updated <file>...] --commit <hash> --config
  reset --commit <hash>
 

Options:
  -h --help     Show this screen.
  --all -a      Validate or upload all questions in the repository (mutually exclusive with <file>...). WARNING: DELETES ALL EXISTING ASSIGNMENTS
  --commit      Specify the commit hash for the upload operation. This option is required when using the `upload` command.
  --config      Indicates that the config file was modified and new assessments might need to be created

Commands:
  validate      Validate the specified question files.
  upload        Parse the specified files and upload them to the database. Requires a commit hash using `--commit <hash>`.
  reset         CLEARS THE DATABASE OF ASSESSMENTS. Then parses the entire repository and uploads the found assessments.

Arguments:
  <file>... List of files to process. Cannot be used with --all.
"""
const val CONFIG_FILEPATH = "config.yaml"
private val configFile = File(CONFIG_FILEPATH)
private val config: Config = parseConfig()

private fun parseConfig(): Config {
    return ObjectMapper(YAMLFactory()).readValue(configFile, Config::class.java)
}

private fun printUsageAndExit(): Nothing {
    println(USAGE)
    exitProcess(1)
}

private fun validateAll() {

}

fun handleValidateCommand(processEntireRepo: Boolean, files: List<String>) {
    files.forEach { filename ->
        try {
            QuestionParser(config).parse(FileInputStream(filename).bufferedReader(), filename)
        } catch (e: QuestionParsingException) {
            throw FileParsingException("Invalid question file", filename, e)
        }
    }
}

private fun uploadAll(commitHash: String) {

    val assessmentProcessor = AssessmentParser(File("."), QuestionParser(config))

    val databaseConfig = DatabaseConfiguration(AzureDatabaseConfigProvider())
    val dataSource = databaseConfig.createDataSource()
    val sessionFactory = databaseConfig.createSessionFactory(dataSource)
    val session = sessionFactory.openSession()
    val queryExecutor = QueryExecutor(session)
    queryExecutor.withTransaction {
        clearDatabase()
        uploadEntities(assessmentProcessor.parseAll(commitHash))
    }
    queryExecutor.closeSession()
}


fun main(args: Array<String>) {
    if (args.size < 2) {
        printUsageAndExit()
    }
    val processEntireRepo = args[1] == "--all" || args[1] == "-a"
    if (processEntireRepo && args.size != 2) { // Can't provide filenames with --all flag set
        printUsageAndExit()
    }

    val command = args[0]
    val arguments = args.drop(1)

    when (command) {
        "help", "--help", "-h" -> printUsageAndExit()
        "validate" -> handleValidateCommand(processEntireRepo, arguments)
        "upload" -> handleUploadCommand(arguments)
        "reset" -> handleResetCommand(arguments)
        else -> printUsageAndExit()
    }
    exitProcess(0)
}


fun handleResetCommand(arguments: List<String>) {
    val commitHash = parseResetCommitHash(arguments)
    uploadAll(commitHash) // Must provide commit hash
}

fun parseResetCommitHash(arguments: List<String>): String {
    if (arguments.size == 2 && arguments[0] == "--commit") {
        return arguments[1]
    }
    printUsageAndExit()
}

fun handleUploadCommand(arguments: List<String>) {
    val parsedArguments = parseUploadArguments(arguments)
    validateUploadArguments(parsedArguments)
    upload(parsedArguments)
}

fun parseUploadArguments(arguments: List<String>): Arguments {
    val deletedFiles = mutableListOf<String>()
    val addedFiles = mutableListOf<String>()
    val updatedFiles = mutableListOf<String>()
    var commitHash: String? = null
    var configFlag = false
    var currentList: MutableList<String>? = null
    var i = 0

    while (i < arguments.size) {
        when (val arg = arguments[i]) {
            "--deleted" -> currentList = deletedFiles
            "--added" -> currentList = addedFiles
            "--updated" -> currentList = updatedFiles
            "--commit" -> {
                if (i + 1 < arguments.size) {
                    commitHash = arguments[i + 1]
                    i++ // Skip the next argument since it's the hash
                } else {
                    printUsageAndExit() // Handle missing hash
                }
            }

            "--config" -> configFlag = true
            else -> {
                if (arg.startsWith("--")) {
                    printUsageAndExit()
                } else {
                    currentList?.add(arg) ?: printUsageAndExit() // No option provided
                }
            }
        }
        i++
    }

    return Arguments(
        deletedFiles = deletedFiles,
        addedFiles = addedFiles,
        updatedFiles = updatedFiles,
        commitHash = commitHash,
        isConfigChanged = configFlag
    )
}


data class Arguments(
    val deletedFiles: List<String>,
    val addedFiles: List<String>,
    val updatedFiles: List<String>,
    val commitHash: String?,
    val isConfigChanged: Boolean = false
)

fun validateUploadArguments(arguments: Arguments) {
    if (arguments.deletedFiles.isEmpty() && arguments.addedFiles.isEmpty() && arguments.updatedFiles.isEmpty()) {
        println("Error: At least one of --deleted, --added, or --updated must be provided with files.")
        printUsageAndExit()
    }
}


fun upload(arguments: Arguments) {
    val databaseConfig = DatabaseConfiguration(AzureDatabaseConfigProvider())
    val dataSource = databaseConfig.createDataSource()
    val sessionFactory = databaseConfig.createSessionFactory(dataSource)

    val (deletedFiles, addedFiles, updatedFiles, commitHash, isConfigChanged) = arguments

    val updater = AssessmentUpdater(sessionFactory, config, commitHash ?: printUsageAndExit())
    validateUploadArguments(arguments)
    println("Uploading changes:")

    if (addedFiles.isNotEmpty()) {
        println("Added files: ${arguments.addedFiles}")
    }
    if (deletedFiles.isNotEmpty()) {
        println("Deleted files: ${arguments.deletedFiles}")
    }
    if (updatedFiles.isNotEmpty()) {
        println("Updated files: ${arguments.updatedFiles}")
    }
    updater.updateAssessments(addedFiles, deletedFiles, updatedFiles, isConfigChanged)
}
