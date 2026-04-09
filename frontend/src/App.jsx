import { useEffect, useMemo, useState } from "react";

const API_BASE = "http://localhost:8080/api";
const COURSE_QUERY_ALIASES = {
  ite: ["ETEC", "CISM", "CSCI", "DATA"],
  itec: ["ETEC", "CISM", "CSCI", "DATA"]
};

async function getJson(path) {
  const response = await fetch(`${API_BASE}${path}`);
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || `Request failed: ${response.status}`);
  }
  return payload;
}

async function postJson(path, body) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || `Request failed: ${response.status}`);
  }
  return payload;
}

function timeToMinutes(value) {
  if (!value || !value.includes(":")) {
    return 9 * 60;
  }
  const [h, m] = value.split(":").map(Number);
  return h * 60 + (m || 0);
}

function minutesToTime(total) {
  const safe = Math.max(0, Math.min(23 * 60 + 59, total));
  const h = String(Math.floor(safe / 60)).padStart(2, "0");
  const m = String(safe % 60).padStart(2, "0");
  return `${h}:${m}`;
}

function addMinutes(value, delta) {
  return minutesToTime(timeToMinutes(value) + delta);
}

export default function App() {
  const [courseCode, setCourseCode] = useState("");
  const [semester, setSemester] = useState("");
  const [professor, setProfessor] = useState("");
  const [options, setOptions] = useState({ courseCodes: [], semesters: [], professors: [] });
  const [imports, setImports] = useState(null);
  const [selectedOffering, setSelectedOffering] = useState(null);
  const [schedule, setSchedule] = useState(null);
  const [calendarData, setCalendarData] = useState(null);
  const [dueDateEdits, setDueDateEdits] = useState({});
  const [eventEdits, setEventEdits] = useState({});
  const [newBlock, setNewBlock] = useState({
    title: "",
    date: "",
    startTime: "15:00",
    endTime: "16:00",
    eventType: "study-block"
  });
  const [plan, setPlan] = useState(null);
  const [assistant, setAssistant] = useState(null);
  const [calendarLink, setCalendarLink] = useState(null);
  const [activeDropdown, setActiveDropdown] = useState("");
  const [optionsLoading, setOptionsLoading] = useState(true);
  const [weekOffset, setWeekOffset] = useState(0);
  const [draggingEventId, setDraggingEventId] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadOptions() {
    setOptionsLoading(true);
    try {
      const optionData = await getJson("/integrations/options");
      setOptions(optionData);
    } catch (err) {
      setError(err.message);
    } finally {
      setOptionsLoading(false);
    }
  }

  useEffect(() => {
    loadOptions();
  }, []);

  const semesterSuggestions = useMemo(() => {
    const importSemesters = imports?.offerings?.map((item) => item.semester) || [];
    return [...new Set([...importSemesters, ...options.semesters])];
  }, [imports, options.semesters]);

  const professorSuggestions = useMemo(() => {
    const importProfessors = imports?.offerings?.map((item) => item.professor) || [];
    return [...new Set([...importProfessors, ...options.professors])];
  }, [imports, options.professors]);

  const filteredCourseCodes = useMemo(() => {
    const query = courseCode.trim().toLowerCase();
    if (!query) {
      return options.courseCodes.slice(0, 25);
    }
    const startsWithMatches = options.courseCodes.filter((item) => item.toLowerCase().startsWith(query));
    if (startsWithMatches.length > 0) {
      return startsWithMatches.slice(0, 25);
    }
    const containsMatches = options.courseCodes.filter((item) => item.toLowerCase().includes(query));
    if (containsMatches.length > 0) {
      return containsMatches.slice(0, 25);
    }
    const aliasPrefixes = COURSE_QUERY_ALIASES[query] || [];
    if (aliasPrefixes.length > 0) {
      const aliasMatches = options.courseCodes.filter((item) => {
        const prefix = item.split(" ")[0];
        return aliasPrefixes.includes(prefix);
      });
      if (aliasMatches.length > 0) {
        return aliasMatches.slice(0, 25);
      }
    }
    // Heuristic fallback: if user types a near-subject code (e.g., ITEC),
    // suggest subjects with similar suffix (e.g., ETEC).
    if (query.length >= 3) {
      const queryPrefix = query.split(" ")[0].toUpperCase();
      const tail = queryPrefix.slice(1);
      const fuzzyMatches = options.courseCodes.filter((item) => {
        const prefix = item.split(" ")[0].toUpperCase();
        return prefix.slice(1).startsWith(tail) || prefix.includes(queryPrefix) || queryPrefix.includes(prefix);
      });
      if (fuzzyMatches.length > 0) {
        return fuzzyMatches.slice(0, 25);
      }
    }
    return [];
  }, [courseCode, options.courseCodes]);

  const calendarWeek = useMemo(() => {
    if (!calendarData?.events?.length) {
      return [];
    }
    const classDates = calendarData.events
      .filter((e) => e.eventType === "class")
      .map((e) => e.date)
      .filter(Boolean)
      .sort();
    const dates = calendarData.events
      .map((e) => e.date)
      .filter(Boolean)
      .sort();
    const firstDate = classDates[0] || dates[0] || new Date().toISOString().slice(0, 10);
    const base = new Date(`${firstDate}T00:00:00`);
    const day = base.getDay();
    const mondayOffset = day === 0 ? -6 : 1 - day;
    base.setDate(base.getDate() + mondayOffset + weekOffset * 7);
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(base);
      d.setDate(base.getDate() + i);
      return d.toISOString().slice(0, 10);
    });
  }, [calendarData, weekOffset]);

  const weekEventsByDay = useMemo(() => {
    const map = {};
    calendarWeek.forEach((day) => {
      map[day] = [];
    });
    (calendarData?.events || []).forEach((event) => {
      if (map[event.date]) {
        map[event.date].push(event);
      }
    });
    Object.keys(map).forEach((day) => {
      map[day].sort((a, b) => `${a.startTime}`.localeCompare(`${b.startTime}`));
    });
    return map;
  }, [calendarData, calendarWeek]);

  const allEventsById = useMemo(() => {
    const map = {};
    (calendarData?.events || []).forEach((event) => {
      map[event.id] = event;
    });
    return map;
  }, [calendarData]);

  const agendaEvents = useMemo(() => {
    return [...(calendarData?.events || [])].sort((a, b) => {
      const left = `${a.date} ${a.startTime}`;
      const right = `${b.date} ${b.startTime}`;
      return left.localeCompare(right);
    });
  }, [calendarData]);

  const filteredSemesters = useMemo(() => {
    const query = semester.trim().toLowerCase();
    if (!query) {
      return semesterSuggestions.slice(0, 20);
    }
    return semesterSuggestions
      .filter((item) => item.toLowerCase().includes(query))
      .slice(0, 20);
  }, [semester, semesterSuggestions]);

  const filteredProfessors = useMemo(() => {
    const query = professor.trim().toLowerCase();
    if (!query) {
      return professorSuggestions.slice(0, 20);
    }
    return professorSuggestions
      .filter((item) => item.toLowerCase().includes(query))
      .slice(0, 20);
  }, [professor, professorSuggestions]);

  async function runImport() {
    setLoading(true);
    setError("");
    try {
      const imported = await getJson(
        `/integrations/import?courseCode=${encodeURIComponent(courseCode)}&semester=${encodeURIComponent(
          semester
        )}&professor=${encodeURIComponent(professor)}`
      );
      setImports(imported);
      setSelectedOffering(null);
      setSchedule(null);
      setCalendarData(null);
      setDueDateEdits({});
      setEventEdits({});
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadOfferingSchedule(offering) {
    setLoading(true);
    setError("");
    try {
      const response = await getJson(
        `/integrations/offering-schedule?courseCode=${encodeURIComponent(
          offering.courseCode
        )}&semester=${encodeURIComponent(offering.semester)}&professor=${encodeURIComponent(offering.professor)}`
      );
      setSelectedOffering(offering);
      setSchedule(response);
      const draft = {};
      response.assignments.forEach((item) => {
        draft[item.title] = item.dueDate;
      });
      setDueDateEdits(draft);
      await loadCalendar(offering);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function saveAssignmentDueDate(assignment) {
    if (!selectedOffering) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      const updated = await postJson("/integrations/assignments/update", {
        courseCode: selectedOffering.courseCode,
        semester: selectedOffering.semester,
        professor: selectedOffering.professor,
        assignmentTitle: assignment.title,
        dueDate: dueDateEdits[assignment.title],
        tentative: false
      });
      setSchedule(updated);
      await loadCalendar(selectedOffering);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function buildPlan() {
    setLoading(true);
    setError("");
    try {
      const weekly = await getJson(
        `/plans/weekly?courseId=${encodeURIComponent(
          selectedOffering
            ? `${selectedOffering.courseCode} ${selectedOffering.semester} ${selectedOffering.professor}`
            : courseCode
        )}`
      );
      setPlan(weekly);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function getAssistant() {
    setLoading(true);
    setError("");
    try {
      const today = await getJson("/assistant/today");
      setAssistant(today);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function linkToCalendar() {
    setLoading(true);
    setError("");
    try {
      const linked = await getJson(
        `/integrations/link?courseCode=${encodeURIComponent(
          selectedOffering?.courseCode || courseCode
        )}&semester=${encodeURIComponent(selectedOffering?.semester || semester)}&professor=${encodeURIComponent(
          selectedOffering?.professor || professor
        )}`
      );
      setCalendarLink(linked);
      await loadCalendar(selectedOffering || { courseCode, semester, professor });
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadCalendar(offering) {
    const target = offering || selectedOffering;
    if (!target) {
      return;
    }
    const data = await getJson(
      `/integrations/calendar?courseCode=${encodeURIComponent(target.courseCode)}&semester=${encodeURIComponent(
        target.semester
      )}&professor=${encodeURIComponent(target.professor)}`
    );
    setCalendarData(data);
    const edits = {};
    data.events.forEach((event) => {
      edits[event.id] = {
        date: event.date,
        startTime: event.startTime,
        endTime: event.endTime
      };
    });
    setEventEdits(edits);
    // Always reset to the calendar's base week when loading a syllabus/offering.
    setWeekOffset(0);
  }

  async function addCalendarBlock() {
    const target = selectedOffering || { courseCode, semester, professor };
    if (!target.courseCode || !target.semester || !target.professor || !newBlock.title || !newBlock.date) {
      setError("Select an offering and fill title/date to add a calendar block.");
      return;
    }
    setLoading(true);
    setError("");
    try {
      const updated = await postJson("/integrations/calendar/block", {
        courseCode: target.courseCode,
        semester: target.semester,
        professor: target.professor,
        title: newBlock.title,
        date: newBlock.date,
        startTime: newBlock.startTime,
        endTime: newBlock.endTime,
        eventType: newBlock.eventType
      });
      setCalendarData(updated);
      setNewBlock((prev) => ({ ...prev, title: "", date: "" }));
      const edits = {};
      updated.events.forEach((event) => {
        edits[event.id] = { date: event.date, startTime: event.startTime, endTime: event.endTime };
      });
      setEventEdits(edits);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function saveCalendarEvent(event) {
    const target = selectedOffering || { courseCode, semester, professor };
    const edit = eventEdits[event.id];
    if (!edit) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      const updated = await postJson("/integrations/calendar/event/update", {
        courseCode: target.courseCode,
        semester: target.semester,
        professor: target.professor,
        eventId: event.id,
        date: edit.date,
        startTime: edit.startTime,
        endTime: edit.endTime
      });
      setCalendarData(updated);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function moveCalendarEvent(eventId, newDate, newStartTime) {
    const event = allEventsById[eventId];
    if (!event || !selectedOffering) {
      return;
    }
    const duration = Math.max(15, timeToMinutes(event.endTime) - timeToMinutes(event.startTime));
    const newEndTime = addMinutes(newStartTime, duration);
    setLoading(true);
    setError("");
    try {
      const updated = await postJson("/integrations/calendar/event/update", {
        courseCode: selectedOffering.courseCode,
        semester: selectedOffering.semester,
        professor: selectedOffering.professor,
        eventId: event.id,
        date: newDate,
        startTime: newStartTime,
        endTime: newEndTime
      });
      setCalendarData(updated);
      const edits = {};
      updated.events.forEach((row) => {
        edits[row.id] = {
          date: row.date,
          startTime: row.startTime,
          endTime: row.endTime
        };
      });
      setEventEdits(edits);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
      setDraggingEventId("");
    }
  }

  return (
    <main className="page">
      <h1>AI Academic Planner</h1>
      <p>Import a course syllabus, build a plan, and link deadlines into a calendar.</p>

      <section className="card">
        <h2>Interactive Demo</h2>
        <label htmlFor="courseCode">Course Code</label>
        <div className="autocomplete">
          <input
            id="courseCode"
            value={courseCode}
            onFocus={() => {
              setActiveDropdown("course");
              if (options.courseCodes.length === 0 && !optionsLoading) {
                loadOptions();
              }
            }}
            onBlur={() => setTimeout(() => setActiveDropdown(""), 150)}
            onChange={(e) => {
              setCourseCode(e.target.value);
              setActiveDropdown("course");
            }}
            placeholder="ITEC 1001"
          />
          {activeDropdown === "course" && (
            <div className="suggestions">
              {optionsLoading ? (
                <div className="noSuggestion">Loading course options...</div>
              ) : filteredCourseCodes.length > 0 ? (
                filteredCourseCodes.map((item) => (
                  <button
                    key={item}
                    type="button"
                    className="suggestionItem"
                    onMouseDown={() => {
                      setCourseCode(item);
                      setActiveDropdown("");
                    }}
                  >
                    {item}
                  </button>
                ))
              ) : (
                <div className="noSuggestion">No matching course codes.</div>
              )}
            </div>
          )}
        </div>
        <button className="secondaryBtn" onClick={loadOptions} disabled={optionsLoading || loading}>
          {optionsLoading ? "Loading Options..." : "Reload Course Options"}
        </button>
        <label htmlFor="semester">Semester</label>
        <div className="autocomplete">
          <input
            id="semester"
            value={semester}
            onFocus={() => setActiveDropdown("semester")}
            onBlur={() => setTimeout(() => setActiveDropdown(""), 150)}
            onChange={(e) => {
              setSemester(e.target.value);
              setActiveDropdown("semester");
            }}
            placeholder="Fall 2026"
          />
          {activeDropdown === "semester" && (
            <div className="suggestions">
              {filteredSemesters.length > 0 ? (
                filteredSemesters.map((item) => (
                  <button
                    key={item}
                    type="button"
                    className="suggestionItem"
                    onMouseDown={() => {
                      setSemester(item);
                      setActiveDropdown("");
                    }}
                  >
                    {item}
                  </button>
                ))
              ) : (
                <div className="noSuggestion">No matching semesters.</div>
              )}
            </div>
          )}
        </div>
        <label htmlFor="professor">Professor</label>
        <div className="autocomplete">
          <input
            id="professor"
            value={professor}
            onFocus={() => setActiveDropdown("professor")}
            onBlur={() => setTimeout(() => setActiveDropdown(""), 150)}
            onChange={(e) => {
              setProfessor(e.target.value);
              setActiveDropdown("professor");
            }}
            placeholder="Brooke Ingram"
          />
          {activeDropdown === "professor" && (
            <div className="suggestions">
              {filteredProfessors.length > 0 ? (
                filteredProfessors.map((item) => (
                  <button
                    key={item}
                    type="button"
                    className="suggestionItem"
                    onMouseDown={() => {
                      setProfessor(item);
                      setActiveDropdown("");
                    }}
                  >
                    {item}
                  </button>
                ))
              ) : (
                <div className="noSuggestion">No matching professors.</div>
              )}
            </div>
          )}
        </div>
        <div className="buttonRow">
          <button onClick={runImport} disabled={loading || !courseCode.trim()}>
            Import Syllabus
          </button>
          <button onClick={buildPlan} disabled={loading || !courseCode.trim()}>
            Build Weekly Plan
          </button>
          <button onClick={getAssistant} disabled={loading}>
            Today Assistant
          </button>
          <button
            onClick={linkToCalendar}
            disabled={loading || !courseCode.trim()}
          >
            Link to Calendar
          </button>
        </div>
        {calendarLink && (
          <p className="success">
            Linked {calendarLink.eventCount} {calendarLink.courseCode} deadlines to calendar.
          </p>
        )}
        {error && <p className="error">{error}</p>}
      </section>

      {calendarLink && (
        <section className="card">
          <h2>Calendar Events</h2>
          <ul>
            {calendarLink.events.map((event) => (
              <li key={`${event.title}-${event.date}`}>
                <strong>{event.title}</strong> on {event.date} ({event.eventType})
              </li>
            ))}
          </ul>
        </section>
      )}

      {imports && (
        <section className="card">
          <h2>Imported Offerings</h2>
          <p>
            {imports.count} offerings found for <strong>{imports.courseCode}</strong>.
          </p>
          {imports.offerings.length === 0 && <p className="muted">No offerings matched your filters.</p>}
          <div className="offeringList">
            {imports.offerings.map((item) => (
              <button
                key={`${item.courseCode}-${item.semester}-${item.professor}-${item.section}`}
                className="offeringBtn"
                onClick={() => loadOfferingSchedule(item)}
                disabled={loading}
              >
                <strong>{item.courseCode}</strong> - {item.semester} - Prof. {item.professor} (Sec {item.section})
              </button>
            ))}
          </div>
        </section>
      )}

      {schedule && selectedOffering && (
        <section className="card">
          <h2>
            Tentative Assignments: {selectedOffering.courseCode} ({selectedOffering.semester}, Prof.{" "}
            {selectedOffering.professor})
          </h2>
          <p className="muted">Update due dates as the official syllabus timeline is released.</p>
          <ul className="assignmentList">
            {schedule.assignments.map((item) => (
              <li key={`${item.title}-${item.type}`} className="assignmentRow">
                <div>
                  <strong>{item.title}</strong> ({item.type}) {item.tentative ? "- tentative" : "- confirmed"}
                </div>
                <div className="dateActions">
                  <input
                    type="date"
                    value={dueDateEdits[item.title] || ""}
                    onChange={(e) =>
                      setDueDateEdits((prev) => ({
                        ...prev,
                        [item.title]: e.target.value
                      }))
                    }
                  />
                  <button onClick={() => saveAssignmentDueDate(item)} disabled={loading}>
                    Save Due Date
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {selectedOffering && (
        <section className="card">
          <h2>
            Calendar Planner: {selectedOffering.courseCode} ({selectedOffering.semester}, Prof.{" "}
            {selectedOffering.professor})
          </h2>
          <p className="muted">
            Class meetings are pre-populated from syllabus context. Add study blocks and adjust times as needed.
          </p>
          <div className="calendarComposer">
            <input
              value={newBlock.title}
              onChange={(e) => setNewBlock((prev) => ({ ...prev, title: e.target.value }))}
              placeholder="Calc II studying"
            />
            <input
              type="date"
              value={newBlock.date}
              onChange={(e) => setNewBlock((prev) => ({ ...prev, date: e.target.value }))}
            />
            <input
              type="time"
              value={newBlock.startTime}
              onChange={(e) => setNewBlock((prev) => ({ ...prev, startTime: e.target.value }))}
            />
            <input
              type="time"
              value={newBlock.endTime}
              onChange={(e) => setNewBlock((prev) => ({ ...prev, endTime: e.target.value }))}
            />
            <select
              value={newBlock.eventType}
              onChange={(e) => setNewBlock((prev) => ({ ...prev, eventType: e.target.value }))}
            >
              <option value="study-block">Study Block</option>
              <option value="class">Class</option>
              <option value="deadline">Deadline</option>
            </select>
            <button onClick={addCalendarBlock} disabled={loading}>
              Add Block
            </button>
            <button onClick={() => loadCalendar(selectedOffering)} disabled={loading}>
              Refresh Calendar
            </button>
          </div>

          {calendarData && (
            <>
              <div className="weekNav">
                <button className="secondaryBtn" onClick={() => setWeekOffset((w) => w - 1)}>
                  Previous Week
                </button>
                <button className="secondaryBtn" onClick={() => setWeekOffset(0)}>
                  This Week
                </button>
                <button className="secondaryBtn" onClick={() => setWeekOffset((w) => w + 1)}>
                  Next Week
                </button>
              </div>
              <div className="weekGrid">
                <div className="timeColumn">
                  {Array.from({ length: 16 }, (_, i) => {
                    const hour = 7 + i;
                    return (
                      <div key={hour} className="timeTick">
                        {`${String(hour).padStart(2, "0")}:00`}
                      </div>
                    );
                  })}
                </div>
                {calendarWeek.map((day) => (
                  <div key={day} className="dayColumn">
                    <div className="dayHeader">{day}</div>
                    <div
                      className="dayCanvas"
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={(e) => {
                        e.preventDefault();
                        const eventId = e.dataTransfer.getData("text/plain") || draggingEventId;
                        if (!eventId) {
                          return;
                        }
                        const rect = e.currentTarget.getBoundingClientRect();
                        const y = Math.max(0, Math.min(rect.height, e.clientY - rect.top));
                        const minuteOffset = Math.round((y / rect.height) * (16 * 60) / 15) * 15;
                        const startMinutes = 7 * 60 + minuteOffset;
                        moveCalendarEvent(eventId, day, minutesToTime(startMinutes));
                      }}
                    >
                      {Array.from({ length: 16 }, (_, i) => (
                        <div key={`${day}-slot-${i}`} className="hourSlot" />
                      ))}
                      {(weekEventsByDay[day] || []).map((event) => {
                        const start = timeToMinutes(event.startTime);
                        const end = timeToMinutes(event.endTime);
                        const topPct = ((start - 7 * 60) / (16 * 60)) * 100;
                        const heightPct = (Math.max(15, end - start) / (16 * 60)) * 100;
                        return (
                          <div
                            key={event.id}
                            className="eventCard"
                            draggable
                            onDragStart={(e) => {
                              e.dataTransfer.setData("text/plain", event.id);
                              setDraggingEventId(event.id);
                            }}
                            style={{
                              top: `${Math.max(0, Math.min(95, topPct))}%`,
                              height: `${Math.max(6, Math.min(80, heightPct))}%`
                            }}
                          >
                            <div>
                              <strong>{event.title}</strong>
                            </div>
                            <small className="muted">
                              {event.startTime} - {event.endTime}
                            </small>
                          </div>
                        );
                      })}
                    </div>
                    {(weekEventsByDay[day] || []).slice(0, 2).map((event) => (
                      <div key={`${event.id}-edit`} className="inlineEventEdit">
                        <input
                          type="time"
                          value={eventEdits[event.id]?.startTime || event.startTime}
                          onChange={(e) =>
                            setEventEdits((prev) => ({
                              ...prev,
                              [event.id]: {
                                ...(prev[event.id] || {}),
                                startTime: e.target.value
                              }
                            }))
                          }
                        />
                        <input
                          type="time"
                          value={eventEdits[event.id]?.endTime || event.endTime}
                          onChange={(e) =>
                            setEventEdits((prev) => ({
                              ...prev,
                              [event.id]: {
                                ...(prev[event.id] || {}),
                                endTime: e.target.value
                              }
                            }))
                          }
                        />
                        <button onClick={() => saveCalendarEvent(event)} disabled={loading}>
                          Save
                        </button>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
              <h3>All Calendar Events</h3>
              <ul className="agendaList">
                {agendaEvents.map((event) => (
                  <li key={`${event.id}-agenda`} className="agendaRow">
                    <div>
                      <strong>{event.title}</strong>{" "}
                      <span className="muted">
                        {event.date} {event.startTime}-{event.endTime} ({event.eventType})
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}

      {plan && (
        <section className="card">
          <h2>
            Weekly Study Plan for {plan.courseId} (Week of {plan.weekOf})
          </h2>
          <div className="calendarGrid">
            {plan.tasks.map((task) => (
              <article key={task.day} className="calendarCard">
                <h3>{task.day}</h3>
                <p>{task.focus}</p>
                <small>{task.minutes} minutes</small>
              </article>
            ))}
          </div>
          <p className="muted">
            Calendar sync: Google ({String(plan.calendar.googleSyncReady)}) / Outlook (
            {String(plan.calendar.outlookSyncReady)})
          </p>
        </section>
      )}

      {assistant && (
        <section className="card">
          <h2>Today Assistant</h2>
          <p>{assistant.message}</p>
          <p>
            <strong>Priority:</strong> {assistant.priority}
          </p>
        </section>
      )}
    </main>
  );
}
