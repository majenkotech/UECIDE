package org.uecide.builtin;

import org.uecide.*;

public class lock_port extends BuiltinCommand {
    public lock_port(Context c) { super(c); }

    public boolean main(String[] arg) throws BuiltinCommandException {
        if (!Serial.waitLock(arg[0])) {
            throw new BuiltinCommandException("Timeout On Port");
        }
        Serial.lockPort(arg[0]);
        return true;
    }

    public void kill() {
    }
}
