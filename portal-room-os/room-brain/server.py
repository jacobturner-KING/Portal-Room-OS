from datetime import datetime
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Portal Room Brain")

state = {
    "weather": "62° · Clear",
    "focus": "Build Portal Room OS v1.",
    "next_event": "9:00 · Morning planning",
    "reminder": "One intentional thing at a time.",
    "home_summary": "Living room · 2 lights on\nThermostat · 70°\nFront door · Locked",
    "music": "Nothing playing",
    "occupied": True,
    "lights_on": True,
    "music_on": False,
}

class Action(BaseModel):
    action: str

@app.get("/api/state")
def get_state():
    return {k: v for k, v in state.items() if k not in {"lights_on", "music_on"}}

@app.post("/api/action")
def action(req: Action):
    if req.action == "toggle_lights":
        state["lights_on"] = not state["lights_on"]
        state["home_summary"] = (
            f"Living room · {'2 lights on' if state['lights_on'] else 'lights off'}\n"
            "Thermostat · 70°\nFront door · Locked"
        )
        return {"ok": True, "message": f"Lights {'on' if state['lights_on'] else 'off'}"}
    if req.action == "toggle_music":
        state["music_on"] = not state["music_on"]
        state["music"] = "Morning playlist · Playing" if state["music_on"] else "Nothing playing"
        return {"ok": True, "message": "Music toggled"}
    if req.action == "voice":
        return {"ok": True, "message": "Voice capture is the next hardware milestone"}
    if req.action == "add_item":
        return {"ok": True, "message": "Add-item sheet is the next interaction milestone"}
    return {"ok": False, "message": f"Unknown action: {req.action}"}

@app.get("/health")
def health():
    return {"ok": True, "time": datetime.now().isoformat()}
