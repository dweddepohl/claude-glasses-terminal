/**
 * Claude Glasses Terminal Server
 *
 * WebSocket server that:
 * 1. Runs Claude Code in a tmux session
 * 2. Forwards terminal output to connected clients
 * 3. Receives input/commands from clients and sends to Claude Code
 * 4. Handles image uploads for Claude's vision capabilities
 */

import { WebSocketServer } from 'ws';
import stripAnsi from 'strip-ansi';
import { writeFileSync, unlinkSync, mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { spawn, execSync } from 'child_process';

const PORT = process.env.PORT || 8080;
const CLAUDE_COMMAND = process.env.CLAUDE_COMMAND || 'claude';
const SESSION_NAME = 'claude-glasses';

// Terminal configuration optimized for glasses HUD display
// Rokid glasses have a narrow monochrome display
const DEFAULT_COLS = 65;  // Narrower for HUD readability
const DEFAULT_ROWS = 15;  // Fewer rows for glasses viewport

class ClaudeTerminalServer {
  constructor() {
    this.wss = null;
    this.clients = new Set();
    this.outputBuffer = [];
    this.maxBufferLines = 500;
    this.tempDir = mkdtempSync(join(tmpdir(), 'claude-glasses-'));
    this.pollInterval = null;
    this.lastOutput = '';
    this.cols = DEFAULT_COLS;
    this.rows = DEFAULT_ROWS;
  }

  start() {
    // Create WebSocket server
    this.wss = new WebSocketServer({ port: PORT });
    console.log(`Claude Glasses Terminal Server listening on port ${PORT}`);

    this.wss.on('connection', (ws) => {
      console.log('Client connected');
      this.clients.add(ws);

      // Send terminal info and current buffer to new client
      this.sendToClient(ws, {
        type: 'terminal_info',
        cols: this.cols,
        rows: this.rows
      });

      this.sendToClient(ws, {
        type: 'terminal_update',
        lines: this.outputBuffer,
        totalLines: this.outputBuffer.length
      });

      ws.on('message', (data) => {
        this.handleClientMessage(ws, data.toString());
      });

      ws.on('close', () => {
        console.log('Client disconnected');
        this.clients.delete(ws);
      });

      ws.on('error', (err) => {
        console.error('WebSocket error:', err);
        this.clients.delete(ws);
      });
    });

    // Setup tmux session
    this.setupTmux();
  }

  setupTmux() {
    // Kill any existing session
    try {
      execSync(`tmux kill-session -t ${SESSION_NAME} 2>/dev/null`);
    } catch (e) {
      // Session didn't exist, that's fine
    }

    // Create new tmux session with Claude Code
    console.log(`Creating tmux session '${SESSION_NAME}' (${this.cols}x${this.rows}) with Claude Code...`);
    try {
      // Create detached session
      execSync(`tmux new-session -d -s ${SESSION_NAME} "${CLAUDE_COMMAND}"`);

      // Force the terminal size - tmux detached sessions need explicit sizing
      execSync(`tmux set-option -t ${SESSION_NAME} window-size manual`);
      execSync(`tmux resize-window -t ${SESSION_NAME} -x ${this.cols} -y ${this.rows}`);

      console.log('tmux session created successfully');

      // Start polling for output
      this.startOutputPolling();
    } catch (e) {
      console.error('Failed to create tmux session:', e.message);
      console.log('Make sure tmux is installed: brew install tmux');
    }
  }

  startOutputPolling() {
    // Poll tmux for output every 100ms
    this.pollInterval = setInterval(() => {
      this.captureOutput();
    }, 100);
  }

  captureOutput() {
    try {
      // Capture the entire visible pane content
      const output = execSync(
        `tmux capture-pane -t ${SESSION_NAME} -p -S -${this.maxBufferLines}`,
        { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }
      );

      // Only broadcast if output changed
      if (output !== this.lastOutput) {
        this.lastOutput = output;

        // Strip ANSI codes and split into lines
        const cleanedOutput = stripAnsi(output);
        const lines = cleanedOutput.split('\n');

        // Update buffer
        this.outputBuffer = lines;

        // Debug: log when broadcasting
        const nonEmptyCount = lines.filter(l => l.trim().length > 0).length;
        console.log(`Broadcasting ${lines.length} lines (${nonEmptyCount} non-empty) to ${this.clients.size} clients`);

        // Remove trailing empty lines but keep structure
        let trimmedLines = [...lines];
        while (trimmedLines.length > 0 && trimmedLines[trimmedLines.length - 1].trim() === '') {
          trimmedLines.pop();
        }

        // Send all content (app will handle scrolling)
        this.broadcast({
          type: 'output',
          data: cleanedOutput,
          lines: trimmedLines,
          totalLines: trimmedLines.length
        });
      }
    } catch (e) {
      // Session might have ended
      console.error('Capture error:', e.message);
      if (e.message.includes('no server running') || e.message.includes("can't find")) {
        console.log('tmux session ended, restarting...');
        this.setupTmux();
      }
    }
  }

  handleClientMessage(ws, message) {
    try {
      const msg = JSON.parse(message);
      console.log('Received:', msg.type, msg.text || msg.key || '');

      switch (msg.type) {
        case 'input':
          // Send text input to tmux
          this.sendToTmux(msg.text);
          break;

        case 'key':
          // Send special key press
          this.handleSpecialKey(msg.key);
          break;

        case 'image':
          // Image from glasses camera
          this.handleImage(msg.data, ws);
          break;

        case 'resize':
          // Terminal resize from client
          if (msg.cols && msg.rows) {
            this.resizeTerminal(msg.cols, msg.rows);
          }
          break;

        default:
          console.warn('Unknown message type:', msg.type);
      }
    } catch (err) {
      console.error('Error handling message:', err);
      this.sendToClient(ws, {
        type: 'error',
        error: err.message
      });
    }
  }

  resizeTerminal(cols, rows) {
    console.log(`Resizing terminal to ${cols}x${rows}`);
    this.cols = cols;
    this.rows = rows;
    try {
      // Resize the tmux pane
      execSync(`tmux resize-pane -t ${SESSION_NAME} -x ${cols} -y ${rows}`);
      // Force output capture after resize
      this.lastOutput = '';  // Force refresh
      setTimeout(() => this.captureOutput(), 100);
    } catch (e) {
      console.error('Error resizing terminal:', e.message);
    }
  }

  sendToTmux(text) {
    try {
      // Escape special characters for tmux
      const escaped = text.replace(/'/g, "'\\''");
      console.log(`Sending to tmux: "${escaped}"`);
      execSync(`tmux send-keys -t ${SESSION_NAME} '${escaped}'`);
      // Force a capture after sending
      setTimeout(() => this.captureOutput(), 50);
    } catch (e) {
      console.error('Error sending to tmux:', e.message);
    }
  }

  handleSpecialKey(key) {
    const keyMap = {
      'escape': 'Escape',
      'enter': 'Enter',
      'tab': 'Tab',
      'shift_tab': 'BTab',
      'up': 'Up',
      'down': 'Down',
      'left': 'Left',
      'right': 'Right',
      'backspace': 'BSpace',
      'ctrl_c': 'C-c',
      'ctrl_d': 'C-d',
      'page_up': 'PageUp',
      'page_down': 'PageDown'
    };

    const tmuxKey = keyMap[key];
    if (tmuxKey) {
      try {
        execSync(`tmux send-keys -t ${SESSION_NAME} ${tmuxKey}`);
      } catch (e) {
        console.error('Error sending key to tmux:', e.message);
      }
    } else {
      console.warn('Unknown key:', key);
    }
  }

  handleImage(base64Data, ws) {
    try {
      // Save image to temp file
      const imagePath = join(this.tempDir, `screenshot-${Date.now()}.png`);
      const imageBuffer = Buffer.from(base64Data, 'base64');
      writeFileSync(imagePath, imageBuffer);

      console.log(`Saved screenshot: ${imagePath}`);

      // For Claude Code, we could potentially use the image path
      // This would require Claude Code to support image input in the current context

      this.sendToClient(ws, {
        type: 'image_received',
        path: imagePath
      });

      // Clean up old screenshots after 5 minutes
      setTimeout(() => {
        try {
          unlinkSync(imagePath);
        } catch (e) {
          // Ignore if already deleted
        }
      }, 5 * 60 * 1000);

    } catch (err) {
      console.error('Error handling image:', err);
      this.sendToClient(ws, {
        type: 'error',
        error: 'Failed to process image'
      });
    }
  }

  sendToClient(ws, message) {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  broadcast(message) {
    const json = JSON.stringify(message);
    for (const client of this.clients) {
      if (client.readyState === client.OPEN) {
        client.send(json);
      }
    }
  }

  stop() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }

    // Kill tmux session
    try {
      execSync(`tmux kill-session -t ${SESSION_NAME}`);
    } catch (e) {
      // Ignore
    }

    if (this.wss) {
      this.wss.close();
    }
  }
}

// Start server
const server = new ClaudeTerminalServer();
server.start();

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('\nShutting down...');
  server.stop();
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.log('Received SIGTERM, shutting down...');
  server.stop();
  process.exit(0);
});
