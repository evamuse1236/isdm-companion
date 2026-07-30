// Which floor each room is on. The LMS only ever gives a room name, so this fills in the
// bit you actually need when you're stood in a stairwell.
//
// Override or extend from .env without touching this file:
//     ROOM_FLOORS=Sahyog:3,Majlis:6,Aangan:1

const DEFAULT_FLOORS = {
  sahyog: 3,
  majlis: 6,
};

const normalise = (room) => String(room || '').trim().toLowerCase();

export function parseRoomFloors(spec) {
  const floors = { ...DEFAULT_FLOORS };
  for (const entry of String(spec || '').split(',').map((s) => s.trim()).filter(Boolean)) {
    const at = entry.lastIndexOf(':');
    if (at < 0) continue;
    const room = normalise(entry.slice(0, at));
    const floor = Number(entry.slice(at + 1).trim());
    if (room && Number.isFinite(floor)) floors[room] = floor;
  }
  return floors;
}

/** Floor number for a room, or null if we don't know it. */
export function floorFor(room, floors = DEFAULT_FLOORS) {
  const hit = floors[normalise(room)];
  return Number.isFinite(hit) ? hit : null;
}

/** "Floor 3" / "Ground floor" / null */
export function floorLabel(room, floors = DEFAULT_FLOORS) {
  const floor = floorFor(room, floors);
  if (floor === null) return null;
  return floor === 0 ? 'Ground floor' : `Floor ${floor}`;
}
