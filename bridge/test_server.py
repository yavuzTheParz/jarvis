import json
import tempfile
import unittest
from pathlib import Path

import server


class ParseReply(unittest.TestCase):
    def test_plain_reply_has_no_actions(self):
        self.assertEqual(server.parse_reply('{"tone":"positive","text":"Of course."}'),
                         {"tone": "positive", "text": "Of course.", "actions": []})

    def test_evenings_are_never_red(self):
        red = {"tone": "warning", "text": "t", "actions": []}
        self.assertEqual(server.evening_calm(dict(red), "Now: EVENING. Today's list: x")["tone"], "normal")
        self.assertEqual(server.evening_calm(dict(red), "Now: AWAKE. Today's list: x")["tone"], "warning")

    def test_prose_never_fails(self):
        self.assertEqual(server.parse_reply("plain text"), {"tone": "normal", "text": "plain text", "actions": []})

    def test_allowed_actions_pass(self):
        raw = """{"tone":"normal","text":"t","actions":[
            {"type":"set_alarm","hour":8,"minute":30,"tomorrow":true},
            {"type":"add_todo","text":" buy milk "},
            {"type":"complete_todo","text":"rapor"},
            {"type":"add_plan","text":"run"},
            {"type":"remove_todo","text":"rapor"},
            {"type":"log_day","text":"went well"},
            {"type":"listen"},
            {"type":"plan_tomorrow"}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "set_alarm", "hour": 8, "minute": 30, "tomorrow": True},
            {"type": "add_todo", "text": "buy milk"},
            {"type": "complete_todo", "text": "rapor"},
            {"type": "add_plan", "text": "run"},
            {"type": "remove_todo", "text": "rapor"},
            {"type": "log_day", "text": "went well"},
            {"type": "listen"},
            {"type": "plan_tomorrow"},
        ])

    def test_every_alarm_of_a_batch_passes(self):
        asked = [{"type": "set_alarm", "hour": 9, "minute": m, "tomorrow": True} for m in range(0, 31, 5)]
        raw = json.dumps({"text": "t", "actions": asked})
        self.assertEqual(server.parse_reply(raw)["actions"], asked)

    def test_anything_else_is_dropped(self):
        raw = """{"text":"t","actions":[
            {"type":"grant_grace","minutes":60},
            {"type":"morning_done"},
            {"type":"open_app","text":"youtube"},
            {"type":"set_alarm","hour":25,"minute":0},
            {"type":"set_alarm","hour":true,"minute":0},
            {"type":"set_alarm","hour":"8","minute":0},
            {"type":"add_todo","text":""},
            {"type":"add_todo","text":5},
            "add_todo"]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [])

    def test_full_control_of_tasks(self):
        raw = """{"text":"t","actions":[
            {"type":"add_todo","text":"berber","date":"2026-11-02"},
            {"type":"edit_todo","text":"buy milk","new_text":"buy yogurt"},
            {"type":"move_todo","text":"rapor","date":"2026-11-04"},
            {"type":"add_routine","text":"running","days":[5,1,1]},
            {"type":"remove_routine","text":"running"},
            {"type":"remember","text":"Gets up at 7"}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "add_todo", "text": "berber", "date": "2026-11-02"},
            {"type": "edit_todo", "text": "buy milk", "new_text": "buy yogurt"},
            {"type": "move_todo", "text": "rapor", "date": "2026-11-04"},
            {"type": "add_routine", "text": "running", "days": [1, 5]},
            {"type": "remove_routine", "text": "running"},
            {"type": "remember", "text": "Gets up at 7"},
        ])

    def test_bad_task_shapes_are_dropped(self):
        for bad in ('{"type":"add_todo","text":"x","date":"pazartesi"}', '{"type":"move_todo","text":"x"}',
                    '{"type":"edit_todo","text":"x"}', '{"type":"edit_todo","text":"x","new_text":" "}',
                    '{"type":"add_routine","text":"x","days":[8]}', '{"type":"add_routine","text":"x","days":[]}',
                    '{"type":"add_routine","text":"x"}', '{"type":"complete_todo","text":"x","date":"2026-11-02"}'):
            self.assertEqual(server.parse_reply('{"text":"t","actions":[%s]}' % bad)["actions"], [], bad)

    def test_notes_stay_on_the_pc(self):
        with tempfile.TemporaryDirectory() as d:
            server.MEMORY, server.NOTES = Path(d), Path(d) / "notes.md"
            left = server.keep_notes([{"type": "remember", "text": "Gets up at 7"}, {"type": "remember", "text": "Gym on Tuesday"},
                                      {"type": "add_todo", "text": "milk"}])
            self.assertEqual(left, [{"type": "add_todo", "text": "milk"}])
            server.keep_notes([{"type": "forget", "text": "tuesday"}])
            self.assertEqual(server.NOTES.read_text().splitlines(), ["Gets up at 7"])

    def test_habit_actions(self):
        raw = """{"text":"t","actions":[
            {"type":"log_habit","text":"Coffee","amount":0.5},{"type":"log_habit","text":"Workout"},
            {"type":"add_habit","text":"Workout","per_week":3},{"type":"add_taper","text":"Coffee","start":2,"step":0.5,"unit":"tablespoon"},
            {"type":"remove_habit","text":"Workout"},
            {"type":"log_habit","text":"Coffee","amount":-1},{"type":"add_habit","text":"x","per_week":9},
            {"type":"add_taper","text":"x","start":2}]}"""
        self.assertEqual([a["type"] for a in server.parse_reply(raw)["actions"]],
                         ["log_habit", "log_habit", "add_habit", "add_taper", "remove_habit"])

    def test_measure_actions(self):
        raw = """{"text":"t","actions":[
            {"type":"log_habit","text":"Food","amount":650,"parts":{"Protein":40,"Carbs":70}},
            {"type":"log_habit","text":"Spending","parts":{" Groceries ":245.5}},
            {"type":"add_measure","text":"Spending","unit":"$","split":true},
            {"type":"add_measure","text":"Food","unit":"kcal","parts":["Protein"],"part_unit":"g"},
            {"type":"log_habit","text":"x","parts":{"a":-1}},{"type":"log_habit","text":"x","parts":[1]},
            {"type":"log_habit","text":"x","amount":2000000},{"type":"add_measure","text":"x"}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "log_habit", "text": "Food", "amount": 650, "parts": {"Protein": 40, "Carbs": 70}},
            {"type": "log_habit", "text": "Spending", "parts": {"Groceries": 245.5}},
            {"type": "add_measure", "text": "Spending", "unit": "$", "parts": [], "part_unit": "", "split": True},
            {"type": "add_measure", "text": "Food", "unit": "kcal", "parts": ["Protein"], "part_unit": "g", "split": False}])

    def test_money_action(self):
        raw = """{"text":"t","actions":[
            {"type":"money","amount":245.5,"category":" Food ","note":"market"},
            {"type":"money","amount":27000,"category":"Salary","income":true,"date":"2026-11-01"},
            {"type":"money","amount":0,"category":"Food"},{"type":"money","amount":5},
            {"type":"money","amount":5,"category":"Food","date":"yesterday"}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "money", "amount": 245.5, "category": "Food", "note": "market", "income": False},
            {"type": "money", "amount": 27000, "category": "Salary", "note": "", "income": True, "date": "2026-11-01"}])

    def test_schedule_action(self):
        good = {"type": "set_schedule", "date": "2026-11-03", "blocks": [{"start": "09:00", "end": "10:30", "text": "Workout"}]}
        self.assertEqual(server.clean_action(good), good)
        self.assertEqual(server.clean_action({"type": "set_schedule", "blocks": []}), {"type": "set_schedule", "blocks": []})
        self.assertEqual(server.clean_action({"type": "listen_plan"}), {"type": "listen_plan"})
        for bad in ([{"start": "25:00", "end": "26:00", "text": "x"}], [{"start": "09:00", "end": "09:00", "text": "x"}],
                    [{"start": "09:00", "end": "10:00", "text": " "}], ["09:00"], "x"):
            self.assertIsNone(server.clean_action({"type": "set_schedule", "blocks": bad}), bad)
        self.assertIsNone(server.clean_action({"type": "set_schedule", "blocks": [], "date": "tomorrow"}))

    def test_capture_action(self):
        idea = {"type": "capture", "kind": "idea", "text": "Floors of the tower turn like a clock", "title": "Clock tower"}
        self.assertEqual(server.clean_action(idea), idea)
        self.assertEqual(server.clean_action({"type": "capture", "kind": "note", "text": " x "}), {"type": "capture", "kind": "note", "text": "x"})
        for bad in ({"kind": "task", "text": "x"},
                    {"kind": "idea", "text": " "},
                    {"kind": "idea", "text": "x", "title": "y" * 200},
                    {"kind": "idea", "text": "x", "title": 5},
                    {"kind": "idea", "text": "x", "date": "2026-11-03"}):
            self.assertIsNone(server.clean_action({"type": "capture", **bad}), bad)

    def test_habit_logged_for_a_past_day(self):
        raw = """{"text":"t","actions":[
            {"type":"log_habit","text":"Expense tracking","date":"2026-11-03"},
            {"type":"log_habit","text":"Coffee","amount":1,"date":"2026-11-03"},
            {"type":"log_habit","text":"x","date":"3 November"},{"type":"log_habit","text":"x","date":5}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "log_habit", "text": "Expense tracking", "date": "2026-11-03"},
            {"type": "log_habit", "text": "Coffee", "amount": 1, "date": "2026-11-03"}])

    def test_reminder_actions(self):
        raw = """{"text":"t","actions":[
            {"type":"add_reminder","text":" Call with Anna ","date":"2026-11-06","time":"21:15"},
            {"type":"add_reminder","text":"tea","time":"9:05"},
            {"type":"add_reminder","text":"laundry","in_minutes":30},
            {"type":"remove_reminder","text":"tea"}]}"""
        self.assertEqual(server.parse_reply(raw)["actions"], [
            {"type": "add_reminder", "text": "Call with Anna", "time": "21:15", "date": "2026-11-06"},
            {"type": "add_reminder", "text": "tea", "time": "9:05"},
            {"type": "add_reminder", "text": "laundry", "in_minutes": 30},
            {"type": "remove_reminder", "text": "tea"},
        ])

    def test_bad_reminders_are_dropped(self):
        for bad in ('{"type":"add_reminder","text":"x"}', '{"type":"add_reminder","text":"","time":"21:15"}',
                    '{"type":"add_reminder","text":"x","time":"24:00"}', '{"type":"add_reminder","text":"x","time":"21.15"}',
                    '{"type":"add_reminder","text":"x","time":"21:15","date":"tomorrow"}',
                    '{"type":"add_reminder","text":"x","in_minutes":0}', '{"type":"add_reminder","text":"x","in_minutes":"5"}',
                    '{"type":"add_reminder","text":"x","in_minutes":99999}',
                    '{"type":"add_reminder","text":"x","in_minutes":5,"time":"21:15"}',
                    '{"type":"remove_reminder","text":"x","date":"2026-11-06"}'):
            self.assertEqual(server.parse_reply('{"text":"t","actions":[%s]}' % bad)["actions"], [], bad)

    def test_news_is_delivered_once(self):
        with tempfile.TemporaryDirectory() as d:
            server.NEWS = Path(d) / "news.jsonl"
            server.say_later("ready")
            self.assertEqual(server.take_news(), ["ready"])
            self.assertEqual(server.take_news(), [])

    def test_a_refused_action_never_reads_as_done(self):
        r = server.parse_reply("""{"tone":"positive","text":"Removed the rule, and the milk is added.","actions":[
            {"type":"remove_morning_rule"},{"type":"add_todo","text":"milk"}]}""")
        self.assertEqual(r["actions"], [{"type": "add_todo", "text": "milk"}])
        self.assertEqual(r["tone"], "caution")
        self.assertNotIn("Removed", r["text"])

    def test_each_conversation_has_its_own_claude_session(self):
        with tempfile.TemporaryDirectory() as d:
            server.STATE = Path(d)
            server.MEMORY = Path(d) / "memory"
            server.NOTES = server.MEMORY / "notes.md"
            server.MEMORY.mkdir()
            server.NOTES.write_text("Gets up at 7\n")
            calls = []

            class Done:
                returncode = 0
                stdout = json.dumps({"result": '{"tone":"normal","text":"t"}'})
                stderr = ""

            def run(args, input, **kw):
                calls.append((args, input))
                return Done()

            real = server.subprocess.run
            server.subprocess.run = run
            try:
                server.ask("hello", "", "d-2026-11-14")
                server.ask("go on", "", "d-2026-11-14")
                server.ask("another topic", "", "x-1731571200000")
            finally:
                server.subprocess.run = real
            first, again, other = calls
            self.assertIn("--session-id", first[0])
            self.assertIn("--resume", again[0])
            self.assertEqual(first[0][first[0].index("--session-id") + 1], again[0][again[0].index("--resume") + 1])
            self.assertIn("--session-id", other[0])
            self.assertNotEqual(first[0][-1], other[0][-1])
            for _, prompt in calls:
                self.assertIn("Gets up at 7", prompt, "notes are shared by every session")
            self.assertTrue((Path(d) / "sessions" / "x-1731571200000").exists())

    def test_session_names_cannot_leave_the_folder(self):
        with tempfile.TemporaryDirectory() as d:
            server.STATE = Path(d)
            for bad in ("../session", "a/b", "", "D-1", "x" * 41, 5):
                with self.assertRaises(ValueError, msg=bad):
                    server.session_file(bad)
            self.assertEqual(server.session_file(None), Path(d) / "session")
            self.assertEqual(server.session_file("legacy"), Path(d) / "sessions" / "legacy")
            (Path(d) / "session").write_text("old")
            self.assertEqual(server.session_file("legacy"), Path(d) / "session", "the old conversation keeps its session")

    def test_actions_of_the_wrong_shape_are_ignored(self):
        self.assertEqual(server.parse_reply('{"text":"t","actions":"add_todo"}')["actions"], [])


if __name__ == "__main__":
    unittest.main()
