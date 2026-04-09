int i;
boolean started;
boolean woolOnly;
boolean pendingPlace;
String face;
Vec3 hitPos;
Vec3 placePos;
Vec3 renderTarget;
float serverYaw;
float serverPitch;
int delayAfterSwap;
int delayAfterAim;
int swapTicks;
int aimTicks;
List<Vec3> buildPositions = new ArrayList<>();
int stuckTicks;
int maxStuckTicks = 60;

void onLoad() {
    modules.registerButton("Wool Only", false);
    modules.registerButton("Render Block", true);
    modules.registerSlider("Delay After Swap", " ticks", 0, 0, 10, 1);
    modules.registerSlider("Delay After Aiming", " ticks", 0, 0, 10, 1);
}

void onEnable() {
    i = 0;
    renderTarget = null;
    started = false;
    pendingPlace = false;
    swapTicks = 0;
    stuckTicks = 0;
    aimTicks = (int) modules.getSlider(scriptName, "Delay After Aiming");
    serverYaw = client.getPlayer().getYaw();
    serverPitch = client.getPlayer().getPitch();
    woolOnly = modules.getButton(scriptName, "Wool Only");

    if (findBuildBlockSlot() == -1) {
        modules.disable(scriptName);
        return;
    }

    buildPositions = calculateBuildPositions();

    for (int j = 0; j < buildPositions.size(); j++) {
        Vec3 pos = buildPositions.get(j);
        Block block = world.getBlockAt((int) pos.x, (int) pos.y, (int) pos.z);
        if (block != null && !block.name.equals("air")) {
            modules.disable(scriptName);
            return;
        }
    }

    started = true;
}

void onDisable() {
}

List<Vec3> calculateBuildPositions() {
    Entity player = client.getPlayer();
    Vec3 playerPos = player.getBlockPosition();
    float yaw = player.getYaw();
    float ny = ((yaw % 360f) + 360f) % 360f;

    int fx, fz, rx, rz;
    if (ny >= 315 || ny < 45) {
        fx = 0; fz = 1; rx = -1; rz = 0;
    } else if (ny >= 45 && ny < 135) {
        fx = -1; fz = 0; rx = 0; rz = -1;
    } else if (ny >= 135 && ny < 225) {
        fx = 0; fz = -1; rx = 1; rz = 0;
    } else {
        fx = 1; fz = 0; rx = 0; rz = 1;
    }

    int px = (int) playerPos.x;
    int pz = (int) playerPos.z;

    int py = (int) playerPos.y;
    for (int scan = py - 1; scan >= py - 5; scan--) {
        Block below = world.getBlockAt(px, scan, pz);
        if (below != null && !below.name.equals("air")) {
            py = scan + 1;
            break;
        }
    }

    int bx = px + fx * 3 + rx;
    int bz = pz + fz * 3 + rz;

    List<Vec3> positions = new ArrayList<>();
    positions.add(new Vec3(bx, py, bz));
    positions.add(new Vec3(bx, py + 1, bz));
    positions.add(new Vec3(bx, py + 2, bz));
    positions.add(new Vec3(px + fx * 3, py + 2, pz + fz * 3));

    return positions;
}

float[] getRotations(float lastYaw, float lastPitch) {
    if (!started) return null;

    woolOnly = modules.getButton(scriptName, "Wool Only");
    delayAfterSwap = (int) modules.getSlider(scriptName, "Delay After Swap");
    delayAfterAim = (int) modules.getSlider(scriptName, "Delay After Aiming");

    while (i < buildPositions.size()) {
        Vec3 pos = buildPositions.get(i);
        Block check = world.getBlockAt((int) pos.x, (int) pos.y, (int) pos.z);
        if (check == null || check.name.equals("air")) break;
        i++;
        stuckTicks = 0;
    }

    if (i >= buildPositions.size()) {
        modules.disable(scriptName);
        return null;
    }

    Vec3 target = buildPositions.get(i);
    if (!hasAdjacentSupport(target)) {
        modules.disable(scriptName);
        return null;
    }
    if (!hasVisiblePlacement(target)) {
        modules.disable(scriptName);
        return null;
    }
    renderTarget = target;

    float baseYaw = serverYaw;
    float basePitch = serverPitch;

    float[] res0 = attemptPlace(baseYaw, basePitch, target);
    if (res0 != null) {
        stuckTicks = 0;
        if (res0[1] == -999f) return new float[]{ baseYaw, basePitch };
        return res0;
    }

    Entity me = client.getPlayer();

    String[] opp = { "DOWN", "UP", "SOUTH", "NORTH", "WEST", "EAST" };
    int[] dx = { 0, 0, 0, 0, 1, -1 };
    int[] dy = { 1, -1, 0, 0, 0, 0 };
    int[] dz = { 0, 0, -1, 1, 0, 0 };

    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);
    float curYawW = normYaw(serverYaw);
    float curPit = serverPitch;

    double INSET = 0.05, STEP = 0.2, JIT = 0.2;
    double insetTop = 1 - INSET - 1e-3, insetBot = INSET + 1e-3;
    int GRID = (int) Math.round(1 / STEP);

    ArrayList<Object[]> cands = new ArrayList<>((GRID + 1) * (GRID + 1) * 6);

    for (int fi = 0; fi < 6; fi++) {
        String f = opp[fi];
        Vec3 support = new Vec3(target.x + dx[fi], target.y + dy[fi], target.z + dz[fi]);
        Block supportBlock = world.getBlockAt(support);
        if (supportBlock == null || supportBlock.name.equals("air")) continue;

        for (int rr = 0; rr <= GRID; rr++) {
            boolean ltr = (rr & 1) == 0;
            double v = rr * STEP + util.randomDouble(-STEP * JIT, STEP * JIT);
            if (v < 0) v = 0; else if (v > 1) v = 1;

            for (int cc = 0; cc <= GRID; cc++) {
                double cu = cc * STEP + util.randomDouble(-STEP * JIT, STEP * JIT);
                if (cu < 0) cu = 0; else if (cu > 1) cu = 1;
                double u = ltr ? cu : 1 - cu;

                double ppx, ppy, ppz;
                if (fi < 2) {
                    ppx = support.x + u; ppz = support.z + v;
                    ppy = support.y + (fi == 1 ? insetTop : insetBot);
                } else if (fi < 4) {
                    ppx = support.x + u; ppy = support.y + v;
                    ppz = support.z + (fi == 2 ? insetTop : insetBot);
                } else {
                    ppz = support.z + u; ppy = support.y + v;
                    ppx = support.x + (fi == 5 ? insetTop : insetBot);
                }

                float[] rotW = getRotationsWrapped(eye, ppx, ppy, ppz);
                float yawW = rotW[0], pit = rotW[1];

                if (Math.abs(pit) > 90f) continue;

                double cost = Math.abs((double) wrapYawDelta(curYawW, yawW))
                            + Math.abs((double) (pit - curPit))
                            + ("UP".equals(f) ? -0.25 : 0);

                cands.add(new Object[]{ cost, Float.valueOf(yawW), Float.valueOf(pit) });
            }
        }
    }

    if (cands.isEmpty()) {
        stuckTicks++;
        if (stuckTicks > maxStuckTicks) modules.disable(scriptName);
        return null;
    }

    cands.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));

    for (int ci = 0; ci < cands.size(); ci++) {
        float yawW = (Float) cands.get(ci)[1];
        float pit = (Float) cands.get(ci)[2];
        float yawUnwrapped = unwrapYaw(yawW, serverYaw);

        float[] res = attemptPlace(yawUnwrapped, pit, target);
        if (res != null) {
            stuckTicks = 0;
            return (res[1] == -999f) ? new float[]{ yawUnwrapped, pit } : res;
        }
    }

    stuckTicks++;
    if (stuckTicks > maxStuckTicks) modules.disable(scriptName);
    return null;
}

float[] attemptPlace(float yaw, float pitch, Vec3 target) {
    if (i >= buildPositions.size()) return null;

    Object[] ray = client.raycastBlock(4.5, yaw, pitch);
    if (ray == null) return null;

    Vec3 hit = (Vec3) ray[0];
    String _face = (String) ray[2];
    Vec3 _place = offsetByFace(hit, _face);
    if (!_place.equals(target)) return null;

    int wantSlot = findBuildBlockSlot();
    if (wantSlot == -1) { modules.disable(scriptName); return null; }

    int curSlot = inventory.getSlot();
    if (curSlot != wantSlot) {
        inventory.setSlot(wantSlot);
        swapTicks = delayAfterSwap;
    }

    if (swapTicks-- > 0) return new float[]{ -999f, -999f };

    if (aimTicks-- > 0 || Math.abs(yaw - serverYaw) > 25 || Math.abs(pitch - serverPitch) > 25)
        return new float[]{ yaw, pitch };

    aimTicks = delayAfterAim;

    hitPos = hit;
    face = _face;
    placePos = ((Vec3) ray[1]).offset(hit.x, hit.y, hit.z);
    pendingPlace = true;

    return new float[]{ yaw, pitch };
}

int findBuildBlockSlot() {
    for (int s = 0; s < 9; s++) {
        ItemStack item = inventory.getStackInSlot(s);
        if (item == null) continue;
        String lower = item.name.toLowerCase();
        if (woolOnly) {
            if (lower.contains("wool")) return s;
        } else {
            if (isBuildingBlock(lower)) return s;
        }
    }
    return -1;
}

boolean isBuildingBlock(String lower) {
    if (lower.contains("sword") || lower.contains("pickaxe") || lower.contains("axe")
        || lower.contains("shovel") || lower.contains("hoe") || lower.contains("bow")
        || lower.contains("arrow") || lower.contains("helmet") || lower.contains("chestplate")
        || lower.contains("leggings") || lower.contains("boots") || lower.contains("potion")
        || lower.contains("pearl") || lower.contains("apple") || lower.contains("bucket")
        || lower.contains("shears") || lower.contains("flint") || lower.contains("fireball")
        || lower.contains("compass") || lower.contains("rod") || lower.contains("egg")) {
        return false;
    }
    return lower.contains("wool") || lower.contains("plank") || lower.contains("end_stone")
        || lower.contains("glass") || lower.contains("sandstone") || lower.contains("cobblestone")
        || lower.contains("stone") || lower.contains("obsidian") || lower.contains("wood")
        || lower.contains("brick") || lower.contains("clay") || lower.contains("concrete")
        || lower.contains("log") || lower.contains("terracotta");
}

float normYaw(float yaw) {
    yaw = ((yaw % 360f) + 360f) % 360f;
    return (yaw > 180f) ? (yaw - 360f) : yaw;
}

float wrapYawDelta(float base, float target) {
    float d = target - base;
    while (d <= -180f) d += 360f;
    while (d > 180f) d -= 360f;
    return d;
}

float unwrapYaw(float yaw, float prevYaw) {
    return prevYaw + ((((yaw - prevYaw + 180f) % 360f) + 360f) % 360f - 180f);
}

float[] getRotationsWrapped(Vec3 eye, double tx, double ty, double tz) {
    double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
    double hd = Math.sqrt(dx * dx + dz * dz);
    float yawWrapped = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    yawWrapped = normYaw(yawWrapped);
    float pitch = (float) Math.toDegrees(-Math.atan2(dy, hd));
    return new float[]{ yawWrapped, pitch };
}

boolean hasAdjacentSupport(Vec3 pos) {
    int x = (int) pos.x, y = (int) pos.y, z = (int) pos.z;
    int[][] offsets = { {0,-1,0}, {0,1,0}, {-1,0,0}, {1,0,0}, {0,0,-1}, {0,0,1} };
    for (int[] o : offsets) {
        Block b = world.getBlockAt(x + o[0], y + o[1], z + o[2]);
        if (b != null && !b.name.equals("air")) return true;
    }
    return false;
}

boolean rayWouldPlaceAt(Vec3 target, float yaw, float pitch) {
    Object[] ray = client.raycastBlock(4.5, yaw, pitch);
    if (ray == null) return false;
    Vec3 hit = (Vec3) ray[0];
    String f = (String) ray[2];
    return offsetByFace(hit, f).equals(target);
}

boolean hasVisiblePlacement(Vec3 target) {
    Entity me = client.getPlayer();
    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);

    if (rayWouldPlaceAt(target, serverYaw, serverPitch)) return true;

    int[] dx = { 0, 0, 0, 0, 1, -1 };
    int[] dy = { 1, -1, 0, 0, 0, 0 };
    int[] dz = { 0, 0, -1, 1, 0, 0 };

    double INSET = 0.05, STEP = 0.2;
    double insetTop = 1 - INSET - 1e-3, insetBot = INSET + 1e-3;
    int GRID = (int) Math.round(1 / STEP);

    for (int fi = 0; fi < 6; fi++) {
        Vec3 support = new Vec3(target.x + dx[fi], target.y + dy[fi], target.z + dz[fi]);
        Block supportBlock = world.getBlockAt(support);
        if (supportBlock == null || supportBlock.name.equals("air")) continue;

        for (int rr = 0; rr <= GRID; rr++) {
            boolean ltr = (rr & 1) == 0;
            double v = rr * STEP;
            if (v > 1) v = 1;

            for (int cc = 0; cc <= GRID; cc++) {
                double cu = cc * STEP;
                if (cu > 1) cu = 1;
                double u = ltr ? cu : 1 - cu;

                double ppx, ppy, ppz;
                if (fi < 2) {
                    ppx = support.x + u; ppz = support.z + v;
                    ppy = support.y + (fi == 1 ? insetTop : insetBot);
                } else if (fi < 4) {
                    ppx = support.x + u; ppy = support.y + v;
                    ppz = support.z + (fi == 2 ? insetTop : insetBot);
                } else {
                    ppz = support.z + u; ppy = support.y + v;
                    ppx = support.x + (fi == 5 ? insetTop : insetBot);
                }

                float[] rotW = getRotationsWrapped(eye, ppx, ppy, ppz);
                float yawW = rotW[0], pit = rotW[1];
                if (Math.abs(pit) > 90f) continue;

                float yawUnwrapped = unwrapYaw(yawW, serverYaw);
                if (rayWouldPlaceAt(target, yawUnwrapped, pit)) return true;
            }
        }
    }
    return false;
}

Vec3 offsetByFace(Vec3 pos, String face) {
    switch (face) {
        case "UP": return pos.offset(0, 1, 0);
        case "DOWN": return pos.offset(0, -1, 0);
        case "NORTH": return pos.offset(0, 0, -1);
        case "SOUTH": return pos.offset(0, 0, 1);
        case "EAST": return pos.offset(1, 0, 0);
        case "WEST": return pos.offset(-1, 0, 0);
        default: return pos;
    }
}

void onPreUpdate() {
    if (!pendingPlace) return;
    pendingPlace = false;
    if (client.placeBlock(hitPos, face, placePos)) {
        client.swing();
        i++;
        stuckTicks = 0;
    }
}

void onRenderWorld(float partialTicks) {
    if (!modules.getButton(scriptName, "Render Block") || renderTarget == null) return;
    try {
        render.block(renderTarget, 0x00FF00, true, true);
    } catch (Exception ignored) {}
}

boolean onPacketSent(CPacket packet) {
    if (packet instanceof C03) {
        C03 c03 = (C03) packet;
        try {
            serverYaw = c03.yaw;
            serverPitch = c03.pitch;
        } catch (Exception ignored) {}
    }
    return true;
}

boolean onMouse(int button, boolean state) {
    if (!started) return true;
    if (button == 1 || button == 0) return false;
    return true;
}
