# ISDM Companion: evolve the selected Glance board design

You are editing a deliberately disposable mobile UI prototype, not production code.

## Mandatory design guidance

Before editing, read and follow this skill in full:

`C:\Users\vishw\.claude\plugins\marketplaces\claude-plugins-official\plugins\frontend-design\skills\frontend-design\SKILL.md`

Also read:

`C:\Users\vishw\.agents\skills\prototype\UI.md`

Apply the design skill's full process: ground the work in the subject; form a compact token/type/layout/signature plan; critique generic choices; build; visually self-critique; remove one unnecessary flourish. Use plain student-facing copy, visible focus, mobile responsiveness, and reduced-motion support.

## The new evidence

The user compared the original three variants and chose **C — Glance board**. We then replaced A and B with two unrelated design directions, and the user again chose C.

That is decisive feedback. C is no longer merely one candidate; its design DNA is the product direction.

The rejected replacements were:

- **Door plate**: room/floor-first campus wayfinding.
- **Hour rail**: a vertical wall-timetable.

Remove those directions completely. Do not revive, lightly restyle, or borrow their main spatial metaphors.

## Task

Keep Variant C as the untouched control. Replace A and B with two **recognizably related but genuinely improved descendants of C**.

First, analyze C in the current `index.html` and state internally why it likely won. Consider its calm visual language, compact glanceability, clear Schedule/Readings split, countdown-led next lecture, and balance between the dominant next item and the rest of the day. Do not assume every element is optimal merely because the whole direction won.

Then create two different improvement hypotheses:

- Each must retain C's product family and visual DNA.
- Each must improve a real usability weakness you identify in C.
- The two variants must test meaningfully different improvements, not tiny spacing or colour changes.
- They may reshape hierarchy and composition, but should still feel like siblings of C.
- At least one should question whether C's two small statistic tiles deserve that much space.
- At least one should improve how the user scans the next lecture and chronological agenda together.
- Neither may become a generic dashboard, a room-first door sign, a wall timetable, a warm-serif editorial page, or a dark neon interface.
- Spend boldness in one useful place per variant and keep the rest restrained.

Name A and B for the improvement hypothesis they embody, not for an aesthetic mood.

## File boundary and protected control

Working file: `index.html` in the current directory.

Read the entire file. Edit only `index.html`.

Variant C is protected:

- Do not modify C's CSS block.
- Do not modify `variantC()`.
- Do not rename C or alter its copy or behavior.
- Do not modify shared behavior in a way that changes C.

Completely replace the current A/B CSS and their `variantA()` / `variantB()` rendering. Remove obsolete Door plate and Hour rail CSS rather than layering overrides.

Do not edit Android production code, the desktop app, docs, credentials, or any other file. Add no dependencies, external fonts, images, network calls, build tooling, or LMS requests. Keep the prototype as one self-contained HTML/CSS/JS file served by the existing local server.

## Product truth

Subject: a native Android daily companion for students at the Indian School of Development Management.

Audience: a student moving between classrooms who needs the next useful answer in seconds, sometimes with weak connectivity.

Single job: **Open the app and immediately know where to go or what to do.**

Simplicity means fewer decisions, not merely fewer visible elements.

### Schedule

- Schedule is home; Today is its default state.
- Exactly one next future lecture is unmistakable.
- Course, time/countdown, room, floor, and trainer are available at a glance.
- Show only the currently useful action: details normally, Mark attendance when open, or none when irrelevant.
- Remaining lectures are chronological; past lectures recede.
- Day navigation is available but quiet.
- Unknown room/floor is calm and never guessed.
- A running lecture is distinct from the next future lecture.

### Readings

- Readings is the only other primary destination.
- Not-done items come first; title and course scan first.
- Section and LMS progress are secondary.
- One tap marks Done; Done items recede and move below a divider.
- Undo remains discoverable.
- Local Done and LMS progress remain visibly distinct concepts.

### Weak connectivity

- Keep cached information visible during refresh or failure.
- Use quiet cached/last-updated messaging.
- Empty, refreshing-cache, offline-cache, and attendance-open scenarios all remain useful.

### Information architecture

- Only Schedule and Readings are primary destinations.
- Attendance and Join belong within Schedule.
- Do not add tabs for tasks, grades, library, reviews, or settings.
- Use plain student language, not LMS terminology.

## Interaction contract to preserve

- `?variant=A|B|C`
- `?scenario=upcoming|live|syncing|offline|empty`
- A/B/C dock buttons and left/right keyboard switching
- Schedule/Readings navigation
- Day selection/navigation
- Lecture detail sheet
- Attendance-open prototype feedback
- Reading Done, reordering, divider count, and Undo
- Refreshing, offline, and quiet-day states
- No persistence and no real mutations

## Completion

Do not return only a plan. Implement both replacements and save `index.html`.

After editing, inspect for accidental similarity, CSS specificity conflicts, overflow, broken hooks, or loss of state behavior. Report:

1. Why C likely won.
2. The usability hypothesis behind new A.
3. The usability hypothesis behind new B.
4. Confirmation that C was preserved and only `index.html` changed.
