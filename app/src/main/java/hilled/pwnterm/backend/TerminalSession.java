package hilled.pwnterm.backend;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public class TerminalSession extends TerminalOutput {

  /**
   * Callback to be invoked when a {@link TerminalSession} changes.
   */
  public interface SessionChangedCallback {
    void onTextChanged(TerminalSession changedSession);

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    void onClipboardText(TerminalSession session, String text);

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

  }

  @SuppressWarnings("JavaReflectionMemberAccess")
  private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
    FileDescriptor result = new FileDescriptor();
    try {
      Field descriptorField;
      try {
        descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
      } catch (NoSuchFieldException e) {
        // For desktop java:
        descriptorField = FileDescriptor.class.getDeclaredField("fd");
      }
      descriptorField.setAccessible(true);
      descriptorField.set(result, fileDescriptor);
    } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
      Log.wtf(EmulatorDebug.LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
      System.exit(1);
    }
    return result;
  }

  private static final int MSG_NEW_INPUT = 1;
  private static final int MSG_PROCESS_EXITED = 4;

  public final String mHandle = UUID.randomUUID().toString();

  private TerminalEmulator mEmulator;

  /**
   * A queue written to from a separate thread when the process outputs, and read by main thread to process by
   * terminal emulator.
   */
  private final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
  /**
   * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
   * writing to the {@link #mTerminalFileDescriptor}.
   */
  private final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
  /**
   * Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue
   */
  private final byte[] mUtf8InputBuffer = new byte[5];

  public SessionChangedCallback getSessionChangedCallback() {
    return mChangeCallback;
  }

  /**
   * Callback which gets notified when a session finishes or changes title.
   */
  private final SessionChangedCallback mChangeCallback;

  /**
   * The pid of the executablePath process. 0 if not started and -1 if finished running.
   */
  private int mShellPid;

  /**
   * The exit status of the executablePath process. Only valid if ${@link #mShellPid} is -1.
   */
  private int mShellExitStatus;

  /**
   * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
   * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int)}.
   */
  private int mTerminalFileDescriptor;

  /**
   * Set by the application for user identification of session, not by terminal.
   */
  public String mSessionName;

  @SuppressLint("HandlerLeak")
  private final Handler mMainThreadHandler = new Handler() {
    final byte[] mReceiveBuffer = new byte[4 * 1024];

    @Override
    public void handleMessage(Message msg) {
      if (msg.what == MSG_NEW_INPUT && isRunning()) {
        int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
        if (bytesRead > 0) {
          mEmulator.append(mReceiveBuffer, bytesRead);
          notifyScreenUpdate();
        }
      } else if (msg.what == MSG_PROCESS_EXITED) {
        int exitCode = (Integer) msg.obj;
        cleanupResources(exitCode);
        mChangeCallback.onSessionFinished(TerminalSession.this);

        String exitDescription = getExitDescription(exitCode);
        byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytesToWrite, bytesToWrite.length);
        notifyScreenUpdate();
      }
    }
  };

  private final String mShellPath;
  private final String mCwd;
  private final String[] mArgs;
  private final String[] mEnv;

  public TerminalSession(String shellPath, String cwd, String[] args, String[] env, SessionChangedCallback changeCallback) {
    mChangeCallback = changeCallback;

    this.mShellPath = shellPath;
    this.mCwd = cwd;
    this.mArgs = args;
    this.mEnv = env;
  }

  /**
   * Inform the attached pty of the new size and reflow or initialize the emulator.
   */
  public void updateSize(int columns, int rows) {
    if (mEmulator == null) {
      initializeEmulator(columns, rows);
    } else {
      JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns);
      mEmulator.resize(columns, rows);
    }
  }

  /**
   * The terminal title as set through escape sequences or null if none set.
   */
  public String getTitle() {
    return (mEmulator == null) ? null : mEmulator.getTitle();
  }

  /**
   * Set the terminal emulator's window size and start terminal emulation.
   *
   * @param columns The number of columns in the terminal window.
   * @param rows    The number of rows in the terminal window.
   */
  public void initializeEmulator(int columns, int rows) {
    mEmulator = new TerminalEmulator(this, columns, rows, /* transcript= */2000);

    int[] processId = new int[1];
    mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns);
    mShellPid = processId[0];

    final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor);

    new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
      @Override
      public void run() {
        try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
          final byte[] buffer = new byte[4096];
          while (true) {
            int read = termIn.read(buffer);
            if (read == -1) return;
            if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
          }
        } catch (Exception e) {
          // Ignore, just shutting down.
        }
      }
    }.start();

    new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
      @Override
      public void run() {
        final byte[] buffer = new byte[4096];
        try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
          while (true) {
            int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
            if (bytesToWrite == -1) return;
            termOut.write(buffer, 0, bytesToWrite);
          }
        } catch (IOException e) {
          // Ignore.
        }
      }
    }.start();

    new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
      @Override
      public void run() {
        int processExitCode = JNI.waitFor(mShellPid);
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
      }
    }.start();
  }

  /**
   * Write data to the executablePath process.
   */
  @Override
  public void write(byte[] data, int offset, int count) {
    if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
  }

  /**
   * Write the Unicode code point to the terminal encoded in UTF-8.
   */
  public void writeCodePoint(boolean prependEscape, int codePoint) {
    if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
      // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
      throw new IllegalArgumentException("Invalid code point: " + codePoint);
    }

    int bufferPosition = 0;
    if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

    if (codePoint <= /* 7 bits */0b1111111) {
      mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
    } else if (codePoint <= /* 11 bits */0b11111111111) {
      /* 110xxxxx leading byte with leading 5 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
    } else if (codePoint <= /* 16 bits */0b1111111111111111) {
      /* 1110xxxx leading byte with leading 4 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
    } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
      /* 11110xxx leading byte with leading 3 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
      /* 10xxxxxx continuation byte with following 6 bits */
      mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
    }
    write(mUtf8InputBuffer, 0, bufferPosition);
  }

  public TerminalEmulator getEmulator() {
    return mEmulator;
  }

  /**
   * Notify the {@link #mChangeCallback} that the screen has changed.
   */
  private void notifyScreenUpdate() {
    mChangeCallback.onTextChanged(this);
  }

  /**
   * Reset state for terminal emulator state.
   */
  public void reset() {
    mEmulator.reset();
    notifyScreenUpdate();
  }

  /**
   * Finish this terminal session by sending SIGKILL to the executablePath.
   */
  public void finishIfRunning() {
    if (isRunning()) {
      try {
        Os.kill(mShellPid, OsConstants.SIGKILL);
      } catch (ErrnoException e) {
        Log.w("neoterm-shell-session",
          "Failed sending SIGKILL: " + e.getMessage());
      }
    }
  }

  protected String getExitDescription(int exitCode) {
    String exitDescription = "\r\n[Process completed";
    if (exitCode > 0) {
      // Non-zero process exit.
      exitDescription += " (code " + exitCode + ")";
    } else if (exitCode < 0) {
      // Negated signal.
      exitDescription += " (signal " + (-exitCode) + ")";
    }
    exitDescription += " - press Enter]";
    return exitDescription;
  }

  /**
   * Cleanup resources when the process exits.
   */
  private void cleanupResources(int exitStatus) {
    synchronized (this) {
      mShellPid = -1;
      mShellExitStatus = exitStatus;
    }

    // Stop the reader and writer threads, and close the I/O streams
    mTerminalToProcessIOQueue.close();
    mProcessToTerminalIOQueue.close();
    JNI.close(mTerminalFileDescriptor);
  }

  @Override
  public void titleChanged(String oldTitle, String newTitle) {
    mChangeCallback.onTitleChanged(this);
  }

  public synchronized boolean isRunning() {
    return mShellPid != -1;
  }

  /**
   * Only valid if not {@link #isRunning()}.
   */
  public synchronized int getExitStatus() {
    return mShellExitStatus;
  }

  @Override
  public void clipboardText(String text) {
    mChangeCallback.onClipboardText(this, text);
  }

  @Override
  public void onBell() {
    mChangeCallback.onBell(this);
  }

  @Override
  public void onColorsChanged() {
    mChangeCallback.onColorsChanged(this);
  }

  public int getPid() {
    return mShellPid;
  }

}
