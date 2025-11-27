# MineQTT Architecture & Key Learnings
Written with the help of GitHub Copilot

## System Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         MineQTT Mod                            │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────┐         ┌─────────────────────────────┐  │
│  │ MQTT Client      │◄────────┤ MineQTT.java                │  │
│  │ (HiveMQ)         │         │ - Connection Management     │  │
│  └────────┬─────────┘         │ - Status Topics (no retain) │  │
│           │                   └─────────────────────────────┘  │
│           │                                                    │
│  ┌────────▼──────────────────────────────────────────────────┐ │
│  │ SubscriptionManager                                       │ │
│  │ - Maps topics → blocks (Set<ICallbackTarget>)             │ │
│  │ - Queues messages for server thread delivery              │ │
│  │ - No cached message delivery (use persistence instead)    │ │
│  └────────┬──────────────────────────────────────────────────┘ │
│           │                                                    │
│           │ onMessageReceived()                                │
│           ▼                                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ RGB LED Block Entities (ICallbackTarget)                │   │
│  │ - parseHomeAssistantJson() → Update state               │   │
│  │ - Thread check: "Server thread" to update topic state   │   │
│  │ - Apply brightness scaling to target colors             │   │
│  └────────┬────────────────────────────────────────────────┘   │
│           │                                                    │
│           │ updateTopicState()                                 │
│           ▼                                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ BlockStateManager                                       │   │
│  │ - ONE state per topic (not per block!)                  │   │
│  │ - topicStates: Map<topic, TopicState>                   │   │
│  │ - topicBlocks: Map<topic, Set<blockPositions>>          │   │
│  │ - Saves to: mineqtt_topic_states.json                   │   │
│  └────────┬────────────────────────────────────────────────┘   │
│           │                                                    │
│           │ BlockStatePersistence                              │
│           ▼                                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ World Save: saves/WorldName/data/mineqtt/               │   │
│  │   mineqtt_topic_states.json                             │   │
│  │   {                                                     │   │
│  │     "topicStates": {                                    │   │
│  │       "minecraft/lamp": {                               │   │
│  │         "targetRed": 15, "brightness": 128, "lit": true,│   │
│  │         "blockPositions": ["overworld:10:64:5", ...]    │   │
│  │       }                                                 │   │
│  │     }                                                   │   │
│  │   }                                                     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ HomeAssistantDiscoveryManager                           │   │
│  │ - Tracks blocks per topic for discovery                 │   │
│  │ - Publishes discovery on first block                    │   │
│  │ - Removes discovery on last block                       │   │
│  │ - Discovery config: retain=false (no ghost messages)    │   │
│  │ - Areas: Overworld, Nether, End                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ MQTT Broker      │
                    │ - Commands: NO   │
                    │   retain         │
                    │ - State: NO      │
                    │   retain         │
                    └──────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ Home Assistant   │
                    │ - RGB LED Lights │
                    │ - JSON Schema    │
                    └──────────────────┘
```

## Key Systems

### 1. Per-Topic State Management
- **One state per topic** - All blocks on same topic share state
- **File**: `mineqtt_topic_states.json`
- **Structure**: `{topic → {targetRGB, brightness, lit, blockPositions[]}}`
- **Single source of truth** - Persistence file, NOT MQTT broker

### 2. State Inheritance Flow
```
New Block Placed → Check getTopicState(topic) → Apply state → Register block
Existing Blocks  → Load from persistence → Restore state → Register block
MQTT Message     → All blocks update → updateTopicState() → Save to manager
```

### 3. Client ID Generation
```java
hostname-based (deterministic) vs UUID.randomUUID() (random)
Result: "minecraft-HOSTNAME" - prevents ghost topics on restart
```

### 4. Message Delivery
- **Queued**: MQTT thread → `lastMessages` → Server pre-tick → Block entities
- **Thread Safety**: Check `Thread.currentThread().getName().contains("Server thread")`
- **Why**: `level.isClientSide` unreliable in message processing context

## Critical Findings

### Issue: `level.isClientSide` Returns True on Server Thread
**Problem**: Block entities can have client-side level references even when on server thread  
**Solution**: Check thread name instead: `Thread.currentThread().getName().contains("Server thread")`  
**Impact**: Without this, topic state never updates, new blocks get stale data

### Issue: Retained Messages Override Persistence
**Problem**: HA publishes commands as retained, overriding saved state on restart  
**Solution**: Set `"retain": false` in discovery config  
**Impact**: Offline color changes ignored, persistence is authoritative

### Issue: Brightness Desync Between Blocks
**Problem**: Blocks stored brightness in NBT, not in shared topic state  
**Solution**: Always override from topic state on load/inheritance  
**Impact**: All blocks now share exact same brightness per topic

### Issue: Partial State Updates (Color Without Brightness)
**Problem**: MQTT message with color but no brightness would reset brightness  
**Solution**: Only apply brightness scaling if target colors exist (> 0)  
**Impact**: State updates are now incremental, not destructive

## Configuration

### MQTT Client
- **Client ID**: `hostname` (deterministic, no "minecraft-" prefix here)
- **Base Topic**: `minecraft-{clientId}` (prefix added here)
- **Status**: Online/Offline (retain=false)

### Home Assistant Discovery
- **Schema**: JSON
- **Retain**: false (commands + state)
- **Color Modes**: rgb, hs, xy
- **Brightness Scale**: 0-255 (HA) → 0-15 (Minecraft)

## Data Flow Example

```
1. Place 3 LEDs on "minecraft/lamp"
   → Block 1: First tick → Creates topic state (R=0, G=0, B=0, brightness=255)
   → Block 2: First tick → Restores from topic state
   → Block 3: First tick → Restores from topic state

2. HA sends: {"color":{"r":255,"g":0,"b":0}, "brightness":128}
   → All 3 blocks receive on Server thread
   → Each calls: updateTopicState("minecraft/lamp", 15, 0, 0, 128, true)
   → Topic state now: R=15, G=0, B=0, brightness=128

3. Place 4th LED on "minecraft/lamp"
   → Inherits topic state → R=15, G=0, B=0, brightness=128 ✓
   → Saves inherited state to ensure consistency

4. Server restart
   → Load mineqtt_topic_states.json
   → All 4 blocks restore: R=15, G=0, B=0, brightness=128 ✓
   → No retained MQTT messages interfere ✓
```

## File Structure

```
saves/WorldName/data/mineqtt/
└── mineqtt_topic_states.json    ← Single source of truth

{
  "topicStates": {
    "minecraft/lamp": {
      "targetRed": 15,
      "targetGreen": 0, 
      "targetBlue": 0,
      "brightness": 128,
      "lit": true,
      "blockPositions": [
        "minecraft:overworld:10:64:5",
        "minecraft:overworld:12:64:5",
        "minecraft:overworld:14:64:5"
      ]
    }
  }
}
```

## Best Practices

1. **Thread Safety**: Always check thread name when updating shared state
2. **State Updates**: Call `updateTopicState()` after ANY state change
3. **Persistence**: Never rely on retained MQTT messages
4. **Discovery**: Use deterministic client IDs, no retained status
5. **Color Scaling**: Apply brightness to target colors, not display colors
6. **State Inheritance**: New blocks MUST inherit from topic state immediately

## Common Pitfalls

❌ Using `level.isClientSide` for server detection  
❌ Storing state per-block instead of per-topic  
❌ Relying on retained MQTT messages  
❌ Not calling `updateTopicState()` after message processing  
❌ Applying brightness changes without preserving colors  

## Performance Notes

- State updates: O(1) per topic (HashMap lookup)
- Block registration: O(1) per block (HashSet add)
- Message delivery: O(n) where n = blocks on topic
- Persistence: Saved on world save, server stop
- Discovery: Updated only on first/last block change

