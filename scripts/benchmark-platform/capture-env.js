#!/usr/bin/env node
import { captureEnvironment } from "./environment.js";

process.stdout.write(JSON.stringify(captureEnvironment(), null, 2) + "\n");
