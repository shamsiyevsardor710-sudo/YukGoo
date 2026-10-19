package uz.yukgo.user;

/** OWNER (ega) > ADMIN > USER */
public enum Role {
    USER(0), ADMIN(1), OWNER(2);

    private final int level;

    Role(int level) { this.level = level; }

    public int level() { return level; }

    public boolean isStaff() { return this != USER; }
}
