package top.msu333.title;

public class PlayerData {
    long lastActiveTime;
    double x, y, z;
    float yaw, pitch;
    boolean afk;
    String previousTeamName;

    public PlayerData(long lastActiveTime, double x, double y, double z, float yaw, float pitch,
                      boolean afk, String previousTeamName) {
        this.lastActiveTime = lastActiveTime;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.afk = afk;
        this.previousTeamName = previousTeamName;
    }

    public void updatePosition(long time, net.minecraft.server.level.ServerPlayer player) {
        this.lastActiveTime = time;
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.yaw = player.getYRot();
        this.pitch = player.getXRot();
    }
}