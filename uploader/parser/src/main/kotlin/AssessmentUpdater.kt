import parser.Config
import org.hibernate.SessionFactory
import parser.Frontmatter
import parser.FrontmatterParser
import parser.QuestionIDUtil
import ut.isep.management.model.entity.*
import java.io.File

class AssessmentUpdater(
    private val sessionFactory: SessionFactory,
    private val commitHash: String
) {
    private val parser = FrontmatterParser()
    private val queryExecutor: QueryExecutor by lazy { QueryExecutor(sessionFactory.openSession()) }
    private val tagToNewAssessment: MutableMap<String, Assessment> = mutableMapOf()
    private val frontmatterToNewAssignment: MutableMap<Frontmatter, Assignment> = mutableMapOf()

    fun updateAssessments(
        addedFilenames: List<String> = listOf(),
        deletedFilenames: List<String> = listOf(),
        modifiedFilenames: List<String> = listOf(),
        config: Config? = null,
    ) {
        tagToNewAssessment.clear()
        frontmatterToNewAssignment.clear()
        queryExecutor.withTransaction {
            if (config != null) {
                updateConfig(config)
            }
            if (addedFilenames.isNotEmpty()) {
                addAssignments(parseAssignments(addedFilenames))
            }
            if (deletedFilenames.isNotEmpty()) {
                val ids = deletedFilenames.mapNotNull { filename ->
                    QuestionIDUtil.parseQuestionID(filename)
                }
                deleteAssignments(ids)
            }
            if (modifiedFilenames.isNotEmpty()) {
                modifyAssignments(parseAssignments(modifiedFilenames))
            }
            upload()
            updateNewAssignmentFileNames()
        }
        queryExecutor.closeSession()
    }

    fun updateHash(newHash: String) {
        queryExecutor.withTransaction {
            updateHashes(commitHash, newHash)
        }
        queryExecutor.closeSession()
    }

    private fun updateConfig(config: Config) {
        val currentActiveAssessmentsByTag: Map<String, Assessment> =
            queryExecutor.getLatestAssessments().associateBy { it.id.tag!! }
        val currentTags: Set<String> = currentActiveAssessmentsByTag.keys
        val newTags: Set<String> = config.tagOptions.subtract(currentTags)
        // create empty assessments for the tags that are in config, but have no active assessments
        val newAssessments = newTags.associateWith { tag ->
            Assessment(
                id = AssessmentID(
                    tag,
                    commitHash
                ),
                latest = true
            )
        }
        val deletedTags: Set<String> = currentTags.subtract(config.tagOptions.toSet())
        // mark assessments whose tags were deleted from the config as inactive
        val deletedAssessments = deletedTags.associateWith { deletedTag ->
            currentActiveAssessmentsByTag[deletedTag]?.apply { latest = false }
                ?: throw IllegalStateException("Could not find assessment with tag $deletedTag to mark as latest=false")
        }

        tagToNewAssessment.putAll(newAssessments)
        tagToNewAssessment.putAll(deletedAssessments)
    }

    private fun upload() {
        queryExecutor.uploadEntities(tagToNewAssessment.values.toList())
        queryExecutor.flush()
        return
    }

    private fun deleteAssignments(deletedQuestionIds: List<Long>) {
        if (deletedQuestionIds.isEmpty()) return

        val assessmentsToUpdate = queryExecutor.getLatestAssessmentByAssignmentIds(deletedQuestionIds)
        assessmentsToUpdate.forEach { assessment ->
            deletedQuestionIds.forEach { deletedQuestionId ->
                deleteAssignmentFromAssessment(assessment, deletedQuestionId)
            }
        }
    }

    private fun deleteAssignmentFromAssessment(
        assessment: Assessment,
        deletedAssignmentId: Long
    ) {
        val tag = assessment.id.tag!!
        val updatedAssessment = tagToNewAssessment.getOrPut(tag) {
            assessment.copyWithoutCloningAssignments()
        }
        updatedAssessment.sections.forEach { section ->
            section.removeAssignmentById(deletedAssignmentId)
        }
    }

    private fun addAssignmentToAssessment(
        assessment: Assessment,
        assignment: Assignment,
    ) {
        val tag = assessment.id.tag!!
        val updatedAssessment = tagToNewAssessment.getOrPut(tag) {
            assessment.copyWithoutCloningAssignments()
        }
        if (!updatedAssessment.latest) {
            throw IllegalStateException("Attempted to add assignment ${assignment.id} to assessment ${assessment.id}")
        }
        val sectionsToUpdate = updatedAssessment.sections.filter { section ->
            section.title == assignment.sectionTitle
        }
        if (sectionsToUpdate.isEmpty()) {
            val newSection = Section(title = assignment.sectionTitle).apply { this.addAssignment(assignment) }
            assessment.addSection(newSection)
        } else if (sectionsToUpdate.size > 1) {
            throw IllegalStateException("Assessments should not have multiple sections with the same title")
        } else {
            sectionsToUpdate[0].addAssignment(assignment)
        }
    }

    private fun getLatestAssessmentByTag(assessmentTag: String): Assessment {
        println("Finding latest assessment for tag $assessmentTag")
        return tagToNewAssessment[assessmentTag]
            ?: queryExecutor.getLatestAssessment(assessmentTag)
    }

    private fun modifyAssignments(frontmatters: List<Frontmatter>) {
        if (frontmatters.isEmpty()) return

        val ids: List<Long> = frontmatters.map {
            it.id ?: throw IllegalStateException(
                "Could not find ID for modified question file at ${it.originalFilePath}"
            )
        }
        val existingAssignments: Map<Long, Assignment> =
            queryExecutor.findAssignmentsByIds(ids).associateBy(Assignment::id)

        for (frontmatter: Frontmatter in frontmatters) {
            val existingAssignment = existingAssignments[frontmatter.id]
                ?: throw IllegalStateException(
                    "For modified file with ID ${frontmatter.id} there is no existing assignment in the database"
                )
            lateinit var updatedAssignment: Assignment

            if (!frontmatter.equalPersistentAttrs(existingAssignment)) {
                updatedAssignment = createNewAssignment(frontmatter)
                handleModifiedAssignment(frontmatter.id!!, updatedAssignment)
            } else {
                updatedAssignment = existingAssignment
            }

            val existingTags = queryExecutor.getTagsOfLatestAssessmentsContainingAssignment(frontmatter.id!!)
            val addedTags: List<String> = frontmatter.tags - existingTags.toSet()
            addedTags.forEach { tag ->
                addAssignmentToAssessment(getLatestAssessmentByTag(tag), updatedAssignment)
            }
            val removedTags: List<String> = existingTags - frontmatter.tags.toSet()
            removedTags.forEach { tag ->
                deleteAssignmentFromAssessment(getLatestAssessmentByTag(tag), frontmatter.id!!)
            }
        }
    }

    private fun handleModifiedAssignment(id: Long, newAssignment: Assignment) {
        val affectedAssessments = queryExecutor.findAssessmentsByAssignmentId(id)
        affectedAssessments.forEach { assessment ->
            val tag = assessment.id.tag!!
            val updatedAssessment = tagToNewAssessment.getOrPut(tag) {
                assessment.copyWithoutCloningAssignments()
            }
            updatedAssessment.sections.forEach { section ->
                section.assignments.replaceAll { oldAssignment ->
                    if (oldAssignment.id == id) newAssignment else oldAssignment
                }
            }
        }
    }

    private fun addAssignments(frontmatters: List<Frontmatter>) {
        frontmatters.forEach { frontmatter ->
            if (frontmatter.id != null) throw FileParsingException(
                "Added files should not have a qid in their filename",
                frontmatter.originalFilePath
            )
            val affectedAssessments = frontmatter.tags.map { tag -> getLatestAssessmentByTag(tag) }
            affectedAssessments.forEach { assessment ->
                val tag = assessment.id.tag!!
                val updatedAssessment = tagToNewAssessment.getOrPut(tag) {
                    assessment.copyWithoutCloningAssignments()
                }
                val newAssignment = createNewAssignment(frontmatter)
                val affectedSections = updatedAssessment.sections.filter { oldSection ->
                    oldSection.title == newAssignment.sectionTitle
                }
                if (affectedSections.isNotEmpty()) {
                    affectedSections.forEach { oldSection ->
                        oldSection.addAssignment(newAssignment)
                    }
                } else {
                    assessment.addSection(Section(title = newAssignment.sectionTitle).apply {
                        addAssignment(newAssignment)
                    })
                }
            }
        }
    }

    private fun updateNewAssignmentFileNames() {
        frontmatterToNewAssignment.forEach { (frontmatter, assignment) ->
            val existingFile = File(frontmatter.originalFilePath)
            val newFilename = QuestionIDUtil.injectQuestionID(assignment.baseFilePath!!, assignment.id)
            existingFile.renameTo(File(newFilename))
        }
    }

    private fun createNewAssignment(frontmatter: Frontmatter): Assignment {
        val newAssignment = Assignment(
            baseFilePath = frontmatter.baseFilePath,
            assignmentType = frontmatter.type,
            availablePoints = frontmatter.availablePoints
        )
        frontmatterToNewAssignment[frontmatter] = newAssignment
        return newAssignment
    }

    private fun Assessment.copyWithoutCloningAssignments(): Assessment {
        val newAssessment = Assessment(
            id = AssessmentID(this.id.tag, commitHash),
            sections = mutableListOf(), // Temporarily empty; will be populated below
            latest = true
        )
        val clonedSections = sections.map { section ->
            Section(
                title = section.title,
                assignments = section.assignments.toMutableList() // Retain original assignment references
            ).also { it.assessment = newAssessment } // Point to the new assessment
        }
        newAssessment.sections.addAll(clonedSections)
        this.latest = false // copied assignment is no longer latest
        return newAssessment
    }

    private fun Frontmatter.equalPersistentAttrs(assignment: Assignment): Boolean {
        return this.id == assignment.id && this.originalFilePath == assignment.baseFilePath && type == assignment.assignmentType
    }

    private fun parseAssignments(filenames: List<String>): List<Frontmatter> {
        return filenames.map { filename ->
            parser.parse(File(filename).readText(), filename).first
        }
    }
}