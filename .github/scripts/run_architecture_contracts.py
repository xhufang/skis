#!/usr/bin/env python3
"""Run required architecture failure contracts; reject empty suites, skips, and expected failures."""

import argparse
from pathlib import Path
import sys
import unittest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    source = args.root.resolve() / ".github/scripts/tests/test_architecture_decisions.py"
    if not source.is_file():
        print(f"Missing required architecture failure contracts: {source}", file=sys.stderr)
        return 1
    suite = unittest.TestLoader().discover(str(source.parent), pattern=source.name)
    if suite.countTestCases() == 0:
        print("No architecture failure contracts were discovered", file=sys.stderr)
        return 1
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if result.skipped:
        print("Required architecture failure contracts must not be skipped", file=sys.stderr)
        return 1
    if result.expectedFailures:
        print("Required architecture failure contracts must not have expected failures", file=sys.stderr)
        return 1
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
