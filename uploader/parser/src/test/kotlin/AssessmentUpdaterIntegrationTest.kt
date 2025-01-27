package ut.isep

import AssessmentUpdater
import QueryExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import parser.Config
import ut.isep.management.model.entity.Assessment
import ut.isep.management.model.entity.Assignment
import ut.isep.management.model.entity.AssignmentType
import ut.isep.management.model.entity.Section
import kotlin.io.path.pathString

class AssessmentUpdaterIntegrationTest : BaseIntegrationTest() {
    @BeforeEach
    override fun openSession() {
        super.openSession()
    }

    @Test
    fun `should handle added question files correctly`() {
        // Arrange
        val topic = "ImaginaryProgramming"
        val filename = "file1.md"
        val content = """
            ---
            type: "multiple-choice"
            tags: 
            - tag1
            points: 2
            seconds: 100
            ---
            Some content here.
        """.trimIndent()
        val testFile = createTestFile(topic, filename, content)
        val assessment = Assessment(tag = "tag1", gitCommitHash = "coolhash", latest = true)
        TestQueryHelper.persistEntity(assessment, session)
        session.close()
        // Act
        val assessmentUpdater =
            AssessmentUpdater(sessionFactory)
        assessmentUpdater.updateAssessments(addedFilenames = (listOf(testFile.path)))
        // Assert an assignment was uploaded
        session = sessionFactory.openSession()
        val retrievedAssignment = TestQueryHelper.fetchSingle<Assignment>(session) ?: fail("Expected one assignment")
        // Assert the basePath was uploaded correctly
        assertEquals(retrievedAssignment.baseFilePath, tempDir.pathString + "/" + topic + "/" + filename)
        // Assert the assignment type was uploaded correctly
        assertEquals(AssignmentType.MULTIPLE_CHOICE, retrievedAssignment.assignmentType)
        // Assert the availablePoints was uploaded correctly
        assertEquals(2, retrievedAssignment.availablePoints)
        // Assert old file no longer exists
        assertFalse(testFile.isFile)
        // Assert there is one new file which includes the qid of the assignment
        val newTestFiles = tempDir.resolve(topic).toFile().listFiles()
            ?: fail("The assignment file is no longer stored in the expected dir ${tempDir.resolve(topic).pathString}")
        assertEquals(1, newTestFiles.size)
        assertEquals(
            tempDir.pathString + "/" + topic + "/" + "file1_qid${retrievedAssignment.id}.md",
            newTestFiles[0].path
        )

    }

    @Test
    fun `should handle deleted question files correctly`() {
        // Given
        val assignment =
            Assignment(
                baseFilePath = "file2.md",
                assignmentType = AssignmentType.OPEN,
                availablePoints = 2,
                availableSeconds = 400,
            )
        val assessment = Assessment(
            tag = "tag2",
            gitCommitHash = "commit",
            latest = true
        ).apply {
            addSection(Section(title = "section1").apply {
                addAssignment(assignment)
            })
        }
        TestQueryHelper.persistEntity(assessment, session)

        // When
        val assessmentUpdater = AssessmentUpdater(sessionFactory)

        assessmentUpdater.updateAssessments(deletedFilenames = listOf("file2_qid${assignment.id}.md"))

        // Then
        val retrievedAssessment = QueryExecutor(session).getLatestAssessment("tag2")
        assertEquals(0, retrievedAssessment.sections.sumOf { it.assignments.size })
    }

    @Test
    fun `should handle modified question files correctly`() {
        // Arrange: add existing assignment in an assessment to database.
        val points = 2
        val seconds = 420L
        var existingAssignment =
            Assignment(
                baseFilePath = "file3.md",
                assignmentType = AssignmentType.MULTIPLE_CHOICE,
                availablePoints = points,
                availableSeconds = seconds
            )
        existingAssignment = TestQueryHelper.persistEntity(existingAssignment, session)
        val existingAssessment = Assessment(tag = "tag3", gitCommitHash = "previousHash", latest = true).apply {
            addSection(Section(title = "TestTopic").apply {
                addAssignment(existingAssignment)
            })
        }
        TestQueryHelper.persistEntity(existingAssessment, session)
        session.close()

        val topic = "TestTopic"
        // Arrange: Update filename to store the autogenerated ID
        val filename = "file3_qid${existingAssignment.id}.md"
        val content = """
            ---
            type: "open"
            tags:
            -  "tag3"
            seconds: $seconds
            points: $points
            ---
            Modified content here.
        """.trimIndent()
        val testFile = createTestFile(topic, filename, content)

        val assessmentUpdater = AssessmentUpdater(sessionFactory)
        // When
        assessmentUpdater.updateAssessments(modifiedFilenames = listOf(testFile.path))

        // Then
        session = sessionFactory.openSession()
        val assignments = TestQueryHelper.fetchAll<Assignment>(session)
        assertEquals(2, assignments.size)
        assertEquals(AssignmentType.MULTIPLE_CHOICE, assignments[0].assignmentType)
        assertEquals(AssignmentType.OPEN, assignments[1].assignmentType)
    }

    @Test
    fun `should handle modified config correctly`() {
        // Arrange one non-active assignment in the database, and an active tag in the config
        val existingInactiveAssessment = Assessment(
            tag = "inactiveTag",
            gitCommitHash = "previousCommitHash",
            latest = null
        )
        val existingActiveAssessment = Assessment(
            tag = "activeTag",
            gitCommitHash = "previousCommitHash",
            latest = true
        )
        TestQueryHelper.persistEntity(existingInactiveAssessment, session)
        TestQueryHelper.persistEntity(existingActiveAssessment, session)
        session.close()
        val config = Config(tagOptions = listOf("newAssessment"), questionOptions = listOf("open", "multipleChoice"))
        val assessmentUpdater = AssessmentUpdater(sessionFactory)

        // Act: Assessmentupdater updates the database to upload an assessment with the active tag
        assessmentUpdater.updateAssessments(config = config)
        session = sessionFactory.openSession()
        // Assert: we find one active and one inactive assessment, sharing a tag (but not commit hashes)
        val assessments = TestQueryHelper.fetchAll<Assessment>(session)
        assertEquals(3, assessments.size)
        val retrievedOldInactiveAssessment = assessments[0]
        val retrievedOldActiveAssessment = assessments[1]
        val retrievedNewActiveAssessment = assessments[2]

        assertEquals(existingInactiveAssessment.id, retrievedOldInactiveAssessment.id)
        assertFalse(retrievedOldInactiveAssessment.isLatest)

        assertEquals(existingActiveAssessment.id, retrievedOldActiveAssessment.id)
        assertFalse(retrievedOldActiveAssessment.isLatest)

        assertEquals("newAssessment", retrievedNewActiveAssessment.tag!!)
        assertNotEquals(retrievedNewActiveAssessment.gitCommitHash, retrievedOldInactiveAssessment.gitCommitHash)
        assertTrue(retrievedNewActiveAssessment.isLatest)
        session.close()
    }

    @Test
    fun `should upload updated assessments`() {
        // Given
        val points = 2
        val seconds = 798L
        val existingAssignment = Assignment(
            baseFilePath = "file4.md", assignmentType = AssignmentType.MULTIPLE_CHOICE, availablePoints = points, availableSeconds = seconds
        ).apply {
            TestQueryHelper.persistEntity(this, session)
        }
        val assessment = Assessment(
            tag = "tag4", gitCommitHash = "testCommit",
            latest = true
        ).apply {
            addSection(Section(title = "section1").apply {
                addAssignment(existingAssignment)
            })
        }
        TestQueryHelper.persistEntity(assessment, session)
        val topic = "Topic1"
        // Arrange: Update filename to store the autogenerated ID
        val filename = "file4_qid${existingAssignment.id}.md"
        // Modify assessment
        val content = """
            ---
            type: "open"
            tags:
            -  "tag4"
            seconds: $seconds
            points: $points
            ---
            Updated content here.
        """.trimIndent()
        val testFile = createTestFile(topic, filename, content)

        val assessmentUpdater =
            AssessmentUpdater(sessionFactory)

        // When
        assessmentUpdater.updateAssessments(modifiedFilenames = listOf(testFile.path))

        // Then
        val updatedAssessments = TestQueryHelper.fetchAll<Assessment>(session)
        assertEquals(2, updatedAssessments.size)
        val updatedAssignment = updatedAssessments[1].sections[0].assignments[0]
        assertEquals(AssignmentType.OPEN, updatedAssignment.assignmentType)
    }

    @Test
    fun `should add a new assignment and update existing assignment tags to include another assessment`() {
        // Arrange: Setup initial state
        val points = 2
        val seconds = 798L
        val topic = "TestTopic"
        val existingAssignment = Assignment(
            baseFilePath = "${tempDir.resolve(topic)}/existingAssignment.md", assignmentType = AssignmentType.OPEN, availablePoints = points, availableSeconds = seconds
        ).apply {
            TestQueryHelper.persistEntity(this, session)
        }
        val assessmentA = Assessment(
            tag = "A", gitCommitHash = "hashA",
            latest = true
        )
        val assessmentB = Assessment(
            tag = "B", gitCommitHash = "hashB",
            latest = true
        ).apply {
            addSection(Section(title = topic).apply {
                addAssignment(existingAssignment)
            })
        }
        TestQueryHelper.persistEntity(assessmentA, session)

        // Arrange: Update filename to store the autogenerated ID
        val filename = "existingAssignment_qid${existingAssignment.id}.md"
        // Modify assessment
        val content = """
            ---
            type: "open"
            tags:
            - "A"
            - "B"
            seconds: $seconds
            points: $points
            ---
            Updated content here.
        """.trimIndent()
        val existingAssignmentFile = createTestFile(topic, filename, content)
        TestQueryHelper.persistEntity(assessmentA, session)
        TestQueryHelper.persistEntity(assessmentB, session)

        val existingID = TestQueryHelper.persistEntity(existingAssignment, session).id
        session.close()

        // Arrange: Prepare the new assignment and updated tags
        val newAssignmentContent = """
        ---
        type: "multiple-choice"
        tags:
        - "B"
        points: 3
        seconds: 200
        ---
        New content for assignment.
    """.trimIndent()
        val newAssignmentFile = createTestFile("TestTopic", "newFile.md", newAssignmentContent)

        val assessmentUpdater = AssessmentUpdater(sessionFactory)

        // Act: Call the updater with the files
        assessmentUpdater.updateAssessments(
            addedFilenames = listOf(newAssignmentFile.path),
            modifiedFilenames = listOf(existingAssignmentFile.path)
        )

        // Assert: Validate the database state
        session = sessionFactory.openSession()
        val updatedAssessments = TestQueryHelper.fetchAll<Assessment>(session) { it.isLatest }
        val updatedAssignment = TestQueryHelper.fetchSingle<Assignment>(session) {it.id == existingAssignment.id}!!
        val newAssignment = TestQueryHelper.fetchSingle<Assignment>(session) { it.id != existingAssignment.id }!!

        // Assert: Check assignment updated in Assessment A
        val assessmentAUpdated = updatedAssessments.find { it.tag == "A"}!!
        assertEquals(1, assessmentAUpdated.sections.sumOf { it.assignments.size })
        assertTrue(assessmentAUpdated.sections.any { section ->
            section.assignments.contains(updatedAssignment)
        })

        // Assert: Assignment added to B
        val assessmentBUpdated = updatedAssessments.find { it: Assessment -> it.tag == "B"}!!
        assertTrue(assessmentBUpdated.sections.any { section ->
            section.assignments.contains(updatedAssignment)
        })
        assertTrue(assessmentBUpdated.sections.any { section ->
            section.assignments.contains(newAssignment)
        })

    }

}
