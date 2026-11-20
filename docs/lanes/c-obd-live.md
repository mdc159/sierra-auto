# Lane C — historical live-Truck scope

Historical branch/scope (2026-11-04): `obd-live`; classic/BLE transports, demo, Truck/Codes/Home, adapter phone settings, permissions and tests. Intended one-command-at-a-time read-only UI with cancellation and reconnect; GM research was separate. Old file-ownership mandates are retired.

## As-is — 2026-11-08, main `210448b`

Demo (default), classic RFCOMM, BLE UART candidates, adapter selection/test, standard PID monitor and Truck/Codes screens exist. Fresh initialization runs on each transport open. No real adapter/ECU acceptance is established; Bluetooth CONNECT/SCAN were ungranted at phone inspection. No vehicle voice tools or live GM UI.

## Next steps

Separate NO DATA/unknown from valid zero codes (#21), classify transient reconnect exceptions so they use the retry budget, and bound missing-ECU cycles while ATRV answers. Verify pairing/UUIDs, supported PIDs, cancellation, reconnect and key-off/sleep before leaving an adapter connected. [OBD reference](../OBD2-PLAN.md), [acceptance plan](../TRUCK-TEST-PLAN.md).
