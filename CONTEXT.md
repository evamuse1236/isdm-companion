# ISDM Companion

ISDM Companion helps a learner use ISDM LMS schedule, attendance, course, and reading data in one Android app. This glossary defines the product terms used by the app.

## Language

**Issue Report**:
A learner-authored description of a problem in the app, with optional Evidence Images.
_Avoid_: Bug ticket, complaint, incident

**Evidence Image**:
A learner-selected image attached to an Issue Report to show the observed problem.
_Avoid_: Proof, automatic screenshot

**Faculty Reference**:
The trainer name or stable identifier that the LMS associates with a Scheduled Session. A name alone does not establish a verified faculty identity.
_Avoid_: Faculty Member when only a trainer name is available

**Faculty Profile**:
The LMS-provided details available for one Faculty Reference. It can contain only a display name when the LMS does not provide a stable profile link or more fields.
_Avoid_: Invented directory profile, learner-authored profile

**Trainer**:
The raw session-level faculty label supplied by the LMS. The app preserves this source label until it can resolve a Faculty Reference.
_Avoid_: Faculty Member, instructor identity

**Scheduled Session**:
A learner-relevant timetable entry assembled from LMS calendar and classroom data.
_Avoid_: Class card, event

**Attendance Session**:
A Scheduled Session with an LMS attendance identifier and therefore a possible Mark action.
_Avoid_: Lecture when attendance identity is relevant

**Attendance Location Gate**:
The rule that permits a Mark action only when recent Location Evidence places the learner inside the Campus Zone.
_Avoid_: Geofence when referring to the product rule

**Campus Zone**:
The 250-metre area around the ISDM Sector 62 campus in which a Mark action can be used.
_Avoid_: Campus when the precise attendance boundary is relevant

**Location Evidence**:
A recent phone location and its measured accuracy, used only to evaluate the Attendance Location Gate.
_Avoid_: Live tracking, attendance proof

**LMS**:
ISDM's learning-management system and the source of schedule, attendance, course, reading, and available faculty data.
_Avoid_: Backend
