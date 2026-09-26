package com.quickbite.order.app;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DebugSwitch {
    private final AtomicBoolean hangDispatcher = new AtomicBoolean(false);
    public boolean hang() { return hangDispatcher.get(); }
    public void setHang(boolean on) { hangDispatcher.set(on); }
}