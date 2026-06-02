# Mob Movement Detection Concept

The current implementation of the Motion Sensor uses a simple AABB (Axis-Aligned Bounding Box) check to detect any
`LivingEntity` (excluding players) within a 5x5x5 area in front of the sensor.

## Proposed Advanced Detection Ideas

### 1. Velocity-Based Detection

Instead of just detecting presence, we can detect actual movement by checking the velocity of entities within the
detection zone.

- **Implementation**: Store the last position of entities or check `entity.getDeltaMovement()`.
- **Benefit**: Reduces false positives from stationary mobs.

### 2. Entity Filtering via "Filter Slots"

The `MotionSensorBlockEntity` already has `NUM_FILTERS = 5` defined but not used. We can use these slots to filter which
mobs trigger the sensor.

- **Implementation**: Use Mob Heads or spawn eggs in the filter slots.
- **Logic**: If filters are present, only entities matching the types in the filters will trigger the sensor.

### 3. Ray-Casting for Line of Sight

To make the sensor more realistic, we can use ray-casting to ensure there is a clear line of sight between the sensor
and the mob.

- **Implementation**: Use `level.clip()` to check for blocks between `pos` and `entity.position()`.
- **Benefit**: Prevents detecting mobs through walls.

### 4. Detection Cones

Instead of a box, use a conical detection area that originates from the sensor's face.

- **Implementation**: Calculate the angle between the sensor's facing vector and the vector to the entity.
- **Logic**: `dot_product(facing_vector, entity_vector) > cos(half_field_of_view)`.

### 5. Configurable Sensitivity and Range

Allow users to configure the range and sensitivity via the GUI.

- **Implementation**: Add more slots or a custom GUI component to set these values.
- **Data**: Store these values in the Block Entity's NBT.

### 6. Heat-Map/Infrared Concept

Simulate a PIR (Passive Infrared) sensor by detecting "heat" changes.

- **Logic**: Mobs have different "heat" levels (e.g., Blazes are hotter than Zombies).
- **Environmental factors**: Torch light or lava could potentially interfere.

## Architecture for Future Blocks

With the new `BaseMqttBlock` and `BaseMqttBlockEntity` architecture, adding new specialized sensors or actuators becomes
easier:

- **Inheritance**: New sensors can extend `MqttPublisherBlockEntity`.
- **Composition**: Use specific "DetectionComponents" that can be plugged into any block entity to give it sensing
  capabilities.
- **Events**: Use a common event system to notify when MQTT messages are received or need to be sent, decoupling the
  MQTT client from the block logic.
