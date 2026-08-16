#!/bin/sh
#
# A Claude Code `SessionEnd` hook that records, in one append, that a session ended and why.
#
# ---------------------------------------------------------------------------------------------
# What it is for
# ---------------------------------------------------------------------------------------------
#
# `:rabosh-samples:runTranscripts` ingests `~/.claude/projects/**/*.jsonl` into a rabosh store. Two
# facts make a hook worth having beside it.
#
#   1. A transcript never records that its session *ended*, or why. `clear`, `resume`, `logout`,
#      `prompt_input_exit`, `bypass_permissions_disabled` and `other` are handed to this hook and
#      kept nowhere else. Appending stdin verbatim is therefore not logging - it is the only copy.
#
#   2. Transcripts are swept by `cleanupPeriodDays`. The corpus is lossy by default, so something
#      has to notice a session finished while the file is still there.
#
# ---------------------------------------------------------------------------------------------
# Why it does not do the ingest
# ---------------------------------------------------------------------------------------------
#
# `SessionEnd` hooks of every type share a **1.5-second budget**. Setting a longer `timeout` raises
# it, up to 60 seconds - and spends every one of those seconds on the user's exit. A JVM start, a
# store open and a few megabytes of JSON do not fit in that, and should not try to: the whole cost
# of this hook is one `cat` and one `printf`, and the ingest happens later, when somebody runs the
# sample. The queue is what carries the fact across that gap.
#
# ---------------------------------------------------------------------------------------------
# Installing it
# ---------------------------------------------------------------------------------------------
#
# Not registered by this repository on purpose. A committed `.claude/settings.json` would switch
# this on for everyone who clones, and a hook that writes to a stranger's home directory should be
# something they typed. Add it to `~/.claude/settings.json` (all projects) or to this project's
# gitignored `.claude/settings.local.json` (just here):
#
#   {
#     "hooks": {
#       "SessionEnd": [
#         {
#           "hooks": [
#             {
#               "type": "command",
#               "command": "\"$CLAUDE_PROJECT_DIR\"/rabosh-samples/hooks/session-end-queue.sh"
#             }
#           ]
#         }
#       ]
#     }
#   }
#
# No `matcher`, because every reason is wanted - a matcher here would silently narrow the corpus.
# No `timeout`, because raising it raises the shared budget and this needs microseconds.
# `$CLAUDE_PROJECT_DIR` is quoted because the path to a checkout contains spaces more often than not.
# Shell form rather than exec form (`"args": []`): exec form spawns the file directly, which a `.sh`
# on Windows is not. In shell form Claude Code uses `sh -c` on macOS and Linux and Git Bash on
# Windows, and this script is POSIX `sh` so that all three are the same script.
#
# Set `RABOSH_TRANSCRIPT_QUEUE` to put the queue somewhere else; `runTranscripts` takes the same path
# as its third argument.
#
# Verify it without ending a session:
#
#   echo '{"hook_event_name":"SessionEnd","reason":"other"}' | rabosh-samples/hooks/session-end-queue.sh
#
set -eu

queue="${RABOSH_TRANSCRIPT_QUEUE:-$HOME/.claude/rabosh-transcripts.queue.jsonl}"

# Read the event before touching the file, so a hook that is going to fail fails before it has
# written half a line.
event="$(cat)"
[ -n "$event" ] || exit 0

# One `printf` rather than `{ cat; echo; }`: two commands under one redirection are two writes, and
# two sessions ending together can interleave them. A single write in append mode lands whole.
# Command substitution has already stripped the trailing newline, and `%s\n` puts back exactly one -
# the queue is JSONL, and `runTranscripts` reads it with the reader written for transcripts.
printf '%s\n' "$event" >> "$queue"
