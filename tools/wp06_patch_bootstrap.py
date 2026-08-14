#!/usr/bin/env python3
from pathlib import Path

path = Path("tools/wp06_review_fix_patch.py")
text = path.read_text(encoding="utf-8")
opening = '    """    public synchronized LoreItemHandoffRecord markReview(\n'
closing = '    public synchronized LoreItemHandoffRecord requestRetry(\n""",\n    "store markReview method",\n)'
if text.count(opening) != 1 or text.count(closing) != 1:
    raise RuntimeError("review patch text-block delimiters are not in the expected staged form")
text = text.replace(opening, "    '''    public synchronized LoreItemHandoffRecord markReview(\n", 1)
text = text.replace(
    closing,
    "    public synchronized LoreItemHandoffRecord requestRetry(\n''',\n    \"store markReview method\",\n)",
    1,
)
path.write_text(text, encoding="utf-8")
