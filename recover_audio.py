import json

log_path = "/Users/lolet/.gemini/antigravity/brain/22b164af-221d-4a83-bdb4-e4598baf95ac/.system_generated/logs/transcript.jsonl"
latest_code = None

with open(log_path, 'r') as f:
    for line in f:
        try:
            step = json.loads(line)
            if "tool_calls" in step:
                for tc in step["tool_calls"]:
                    # check for write_to_file or multi_replace_file_content or replace_file_content
                    # But the easiest is to look at view_file outputs!
                    pass
            # Or just check the step's content if it contains the file content from view_file response
            if step.get("type") == "TOOL_CALL_RESPONSE" and step.get("status") == "DONE":
                for resp in step.get("responses", []):
                    if resp.get("name") == "default_api:view_file":
                        out = resp.get("response", {}).get("output", "")
                        if "AudioDecoder.kt" in out and "class AudioDecoder" in out:
                            latest_code = out
        except Exception as e:
            pass

if latest_code:
    print(latest_code[:500])
    with open("AudioDecoder_recovered.txt", "w") as out_f:
        out_f.write(latest_code)
