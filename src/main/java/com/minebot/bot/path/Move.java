package com.minebot.bot.path;

/** How the bot gets from one path node to the next. */
public enum Move {
    START,
    /** Step to an adjacent block on the same level. */
    WALK,
    DIAGONAL,
    /** Jump up one block onto the neighbour. */
    ASCEND,
    /** Walk off an edge and drop down. */
    DESCEND,
    /** Place a block under the next spot and walk onto it. */
    BRIDGE,
    /** Jump and place a block underneath. */
    PILLAR,
    /** Put a ladder on the wall of a building and climb it one block. */
    LADDER,
    /** Mine the block below and drop into it. */
    DIG_DOWN,
    /** Dig one step of a stairway: the block ahead and below, with room to walk back up. */
    STAIR_DOWN,
    SWIM_UP,
    SWIM_DOWN,
    CLIMB_UP,
    CLIMB_DOWN
}
