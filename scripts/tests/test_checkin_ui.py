"""Source-level UI regression checks; device rendering still requires Android."""
from pathlib import Path
import unittest


class CheckInUiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (Path(__file__).parents[2] / "shared/src/commonMain/kotlin/org/intentional/shared/IntentionalApp.kt").read_text()

    def test_fulfillment_buttons_only_appear_in_final_reflection(self):
        render = self.source.split("when (s.stage) {", 1)[1].split("Stage.SAVED ->", 1)[0]
        self.assertEqual(1, render.count("OutcomeButtons("))
        before_reflection, reflection = render.split("Stage.REFLECT ->", 1)
        self.assertNotIn("OutcomeButtons(", before_reflection)
        self.assertIn("OutcomeButtons(yes = { engine.complete(true) }, no = { engine.complete(false) })", reflection)

    def test_expiry_is_a_navigation_choice_not_a_second_completion_question(self):
        expired = self.source.split("Stage.EXPIRED ->", 1)[1].split("Stage.EXTEND ->", 1)[0]
        self.assertIn('Primary("Finish and check in") { engine.reflect() }', expired)
        self.assertIn('Secondary("Add more time") { engine.requestExtension() }', expired)
        self.assertNotIn("OutcomeButtons(", expired)
        self.assertNotIn("Did you finish", expired)

    def test_no_manual_export_or_submission_controls_remain(self):
        for obsolete in ["Export study data", "Export session journal", "shareStudyData", "submitStudyData", "exportHistory", "StudySubmissionControls"]:
            self.assertNotIn(obsolete, self.source)
        activity = (Path(__file__).parents[2] / "androidApp/src/main/kotlin/org/intentional/app/MainActivity.kt").read_text()
        self.assertNotIn("ACTION_SEND", activity)
        self.assertNotIn("CreateDocument", activity)

    def test_one_time_notice_lists_the_fields_and_requires_acknowledgment(self):
        self.assertIn("if (!device.studyNoticeAcknowledged)", self.source)
        self.assertIn("actions.acknowledgeStudyDataNotice()", self.source)
        for field in ["pseudonymous", "opening attempts", "timestamps", "intentions", "extension reasons", "selected durations", "tracked usage", "relaxation ratings", "task-fulfillment"]:
            self.assertIn(field, self.source)


if __name__ == "__main__":
    unittest.main()
