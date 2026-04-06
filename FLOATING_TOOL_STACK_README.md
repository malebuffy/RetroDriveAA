# Floating Tool Stack & Virtual D-Pad System

## Overview

Your DOSBox Android app now has a modular floating tool system with a fully configurable virtual D-Pad. This implementation provides three key components:

## 1. Floating Tool Stack (3 FABs)

Located in the **bottom-right corner**, vertically stacked:

### Top FAB (Orange - Settings)
- **Action**: Opens D-Pad configuration dialog
- **Purpose**: Customize key mappings for each D-Pad direction
- **Icon**: Should be ic_settings (currently styled circular)

### Middle FAB (Green - D-Pad Toggle)
- **Action**: Shows/hides the virtual D-Pad overlay
- **Purpose**: Toggle D-Pad visibility when needed
- **Icon**: Should be ic_gamepad (currently styled circular)

### Bottom FAB (Blue - Keyboard)
- **Action**: Toggles system soft keyboard
- **Purpose**: Existing keyboard functionality
- **Icon**: Should be ic_keyboard (currently styled circular)

## 2. Virtual D-Pad View

Located in the **bottom-left corner** (200dp x 200dp):

### Features:
- **Semi-transparent overlay**: Black center with white arrows
- **Four directions**: Up, Down, Left, Right
- **Multi-touch support**: Hold multiple directions simultaneously
- **Dynamic key mapping**: Each direction reads from SharedPreferences
- **Visual feedback**: Arrows turn green when pressed

### Default Mappings:
- Up → KEYCODE_DPAD_UP
- Down → KEYCODE_DPAD_DOWN
- Left → KEYCODE_DPAD_LEFT
- Right → KEYCODE_DPAD_RIGHT

### Touch Detection:
- **Center circle**: No action (innerRadius)
- **Directional sectors**: 45-degree sectors for each direction
- **Outside radius**: No action
- **Angle calculation**: Uses atan2 for precise direction detection

## 3. D-Pad Configuration Dialog

### Features:
- **4 configuration rows**: One for each direction (Up, Down, Left, Right)
- **Current key display**: Shows KeyCode name (e.g., "W", "ARROW_UP")
- **Assign button**: Opens key capture dialog
- **Reset button**: Restores default arrow keys

### Key Capture Mechanism (The "Hidden Input Trick"):

When you tap an "Assign" button:
1. **Dialog appears**: "Press any key for [DIRECTION]..."
2. **Hidden EditText**: 1px invisible field auto-focused
3. **Keyboard shown**: Soft keyboard appears automatically
4. **Key capture**: OnKeyListener captures the pressed key
5. **Save to prefs**: KeyCode saved to SharedPreferences
6. **UI update**: Label shows new key name
7. **Dialog dismissed**: Closes automatically

### Supported Keys:
- Hardware keyboard keys (A-Z, arrows, modifiers, etc.)
- Software keyboard taps (when using on-screen keyboard)
- Any Android KeyEvent keyCode

## 4. Integration in DOSBoxActivity

### Implementation:
```java
public class DOSBoxActivity extends SDLActivity implements VirtualDPadView.OnDPadEventListener
```

### Key Methods:

#### `onDPadPress(int direction, int keyCode)`
- Called when user presses a D-Pad direction
- Creates KeyEvent.ACTION_DOWN event
- Routes through InputDirector
- Falls back to SDL's dispatchKeyEvent

#### `onDPadRelease(int direction, int keyCode)`
- Called when user releases a D-Pad direction
- Creates KeyEvent.ACTION_UP event
- Routes through InputDirector
- Falls back to SDL's dispatchKeyEvent

### Initialization Flow:
1. `onCreate()` → Initialize InputDirector
2. Post to UI thread → Add VirtualDPad and ToolStack
3. `addVirtualDPad()` → Create D-Pad (initially hidden)
4. `addFloatingToolStack()` → Create 3 FABs
5. `setOnDPadEventListener(this)` → Wire up event handling

## Usage Instructions

### For End Users:

1. **Launch the app** - DOSBox starts with floating buttons visible
2. **Tap green D-Pad button** - Virtual D-Pad appears in bottom-left
3. **Use D-Pad** - Touch/swipe in any direction to move
4. **Multi-touch** - Hold multiple directions (e.g., diagonal movement)
5. **Tap orange settings** - Customize key mappings
6. **Assign keys** - In settings, tap "Assign" → Press desired key
7. **Tap blue keyboard** - Toggle soft keyboard for text input

### For WASD Controls (Common for DOS games):

1. Tap orange settings button
2. Tap "Assign" next to UP → Press W
3. Tap "Assign" next to LEFT → Press A
4. Tap "Assign" next to DOWN → Press S
5. Tap "Assign" next to RIGHT → Press D
6. Tap "Done"
7. Now D-Pad maps to WASD cluster!

### For Arrow Keys (Default):

Already configured! Just tap the green D-Pad toggle to show/hide.

## File Structure

```
app/src/main/java/com/dosbox/emu/
├── VirtualDPadView.java        # Custom D-Pad view with multi-touch
├── DPadConfigDialog.java       # Configuration dialog with key capture
└── DOSBoxActivity.java         # Main activity (modified)
```

## SharedPreferences Storage

**Preference File**: `dpad_config`

**Keys**:
- `dpad_key_up` → int (KeyCode for UP)
- `dpad_key_down` → int (KeyCode for DOWN)
- `dpad_key_left` → int (KeyCode for LEFT)
- `dpad_key_right` → int (KeyCode for RIGHT)

## Technical Details

### VirtualDPadView

**Touch Handling**:
- `ACTION_DOWN` / `ACTION_POINTER_DOWN` → Press direction
- `ACTION_MOVE` → Update active pointers
- `ACTION_UP` / `ACTION_POINTER_UP` → Release direction
- `ACTION_CANCEL` → Release all directions

**Direction Calculation**:
```java
double angle = Math.toDegrees(Math.atan2(dy, dx));
// 0° = Right, 90° = Down, 180° = Left, 270° = Up
```

**Multi-touch Support**:
- `SparseArray<Integer> activePointers` → Maps pointer ID to direction
- `boolean[] directionPressed` → Tracks pressed state
- Prevents duplicate press/release events

### DPadConfigDialog

**Key Capture Strategy**:
1. Create invisible EditText (1px x 1px, alpha=0)
2. Request focus + show keyboard via InputMethodManager
3. Attach OnKeyListener to capture hardware keys
4. Attach OnEditorActionListener to capture soft keyboard
5. Save keyCode to SharedPreferences on first key press
6. Dismiss dialog and hide keyboard

### DOSBoxActivity Integration

**Event Flow**:
```
User touches D-Pad
  ↓
VirtualDPadView.onTouchEvent()
  ↓
onDPadPress(direction, keyCode)
  ↓
Create KeyEvent(ACTION_DOWN, keyCode)
  ↓
inputDirector.processKeyEvent()
  ↓
NativeBridge.sendKey() → SDL → DOSBox core
```

## Customization Options

### Change D-Pad Size:
```java
// In addVirtualDPad()
int dpadSize = (int) (200 * density); // Change 200 to desired dp
```

### Change D-Pad Position:
```java
// In addVirtualDPad()
params.gravity = Gravity.BOTTOM | Gravity.START; // Change gravity
```

### Change FAB Colors:
```java
settingsButton = createFAB(0xCCFF9800, fabSize); // Orange
dpadToggleButton = createFAB(0xCC4CAF50, fabSize); // Green
keyboardButton = createFAB(0xCC2196F3, fabSize); // Blue
```

### Add Icon Resources (Optional):

If you want to use actual icons instead of solid colors:

1. Add icons to `res/drawable/`:
   - `ic_settings.xml`
   - `ic_gamepad.xml`
   - `ic_keyboard.xml`

2. Modify `createFAB()`:
```java
fab.setImageResource(R.drawable.ic_settings); // Add this line
```

## Testing Checklist

- [ ] Tool stack appears in bottom-right
- [ ] D-Pad toggle shows/hides virtual D-Pad
- [ ] Settings button opens configuration dialog
- [ ] Keyboard button toggles soft keyboard
- [ ] Virtual D-Pad responds to touch
- [ ] Multi-touch works (press multiple directions)
- [ ] Key capture dialog appears
- [ ] Hardware keys are captured correctly
- [ ] Soft keyboard keys are captured correctly
- [ ] Key mappings persist after app restart
- [ ] Reset to defaults works
- [ ] D-Pad sends correct keys to DOSBox
- [ ] Visual feedback (green arrows) works

## Known Limitations

1. **Icon placeholders**: FABs use solid colors, not vector icons
2. **Single key mapping**: Each direction maps to one key (not key combos)
3. **No diagonal sectors**: Diagonals map to nearest cardinal direction
4. **Fixed size**: D-Pad size is hardcoded (not dynamic)
5. **No haptic feedback**: Could add vibration on touch

## Future Enhancements

- [ ] Add vector icons for FABs
- [ ] Support key combinations (e.g., Ctrl+W)
- [ ] Add diagonal sectors (8-way D-Pad)
- [ ] Make D-Pad size configurable
- [ ] Add haptic feedback option
- [ ] Allow drag-to-reposition D-Pad
- [ ] Add button mapping (A/B buttons)
- [ ] Profile system (save multiple configurations)
- [ ] Import/export configurations

## Build Status

✅ **Successfully compiled** - No errors
✅ **All classes created** - VirtualDPadView, DPadConfigDialog
✅ **DOSBoxActivity integrated** - OnDPadEventListener implemented
✅ **Strategy Pattern preserved** - Works with existing InputDirector

## APK Location

`app/build/outputs/apk/debug/app-debug.apk`

Install with:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

**Implementation Complete!** 🎮

Your DOSBox app now has a professional-grade floating tool system with a fully configurable virtual D-Pad. The modular architecture makes it easy to extend with additional controls (buttons, joysticks, etc.) in the future.
