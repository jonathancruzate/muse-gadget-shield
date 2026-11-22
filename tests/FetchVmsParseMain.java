package com.jonathancruzate.musegadgetshield;

import java.io.IOException;
import java.util.List;

/**
 * Desktop regression check for MuseApi.parseFetchVms (v0.4.5).
 *
 * The real Muse API returns the VM list under "vm_list" with entries
 * keyed "vm_id" / "vm_auth_token" / "default" (SDK muse_api.py, ESP32
 * vm_api.c). The Shield port originally read "vms" / "auth_token" /
 * "is_default", so every healthy HTTP 200 parsed as 0 VMs and pairing
 * was refused with "the account has no VM yet" — while the desktop
 * fake VM, built to our own wrong schema, kept the loopback green.
 * This fixture pins the REAL schema so the drift cannot regress.
 */
public final class FetchVmsParseMain {
    private static void check(boolean cond, String label) {
        if (!cond) throw new AssertionError("FETCHVMS PARSE FAIL: " + label);
        System.out.println("FETCHVMS PARSE ok: " + label);
    }

    public static void main(String[] args) throws Exception {
        // 1) Real-schema success fixture (the exact shape from the task).
        String body = "{\"vm_list\":[{\"vm_id\":\"vm-test\","
                + "\"vm_auth_token\":\"synthetic\","
                + "\"vm_url\":\"wss://example.invalid\",\"default\":true}]}";
        List<MuseApi.Vm> vms = MuseApi.parseFetchVms(Json.parse(body), 200);
        check(vms.size() == 1, "real schema parses 1 VM");
        check("vm-test".equals(vms.get(0).vmId), "vm_id mapped");
        check("synthetic".equals(vms.get(0).authToken), "vm_auth_token mapped");
        check(vms.get(0).isDefault, "\"default\":true mapped");
        check(MuseApi.pickVm(vms) == vms.get(0), "pickVm returns the default");

        // 2) Entries missing vm_id or vm_auth_token are skipped; a
        // "default" absent entry is not default.
        String mixed = "{\"vm_list\":["
                + "{\"vm_id\":\"no-token\"},"
                + "{\"vm_auth_token\":\"no-id\"},"
                + "{\"vm_id\":\"vm-plain\",\"vm_auth_token\":\"tok-plain\"}]}";
        List<MuseApi.Vm> vms2 = MuseApi.parseFetchVms(Json.parse(mixed), 200);
        check(vms2.size() == 1 && "vm-plain".equals(vms2.get(0).vmId)
                && !vms2.get(0).isDefault, "incomplete entries skipped");

        // 3) An error payload on HTTP 200 is a failure, and the thrown
        // message carries the backend error CODE (never the body).
        String err = "{\"error_title\":\"Synthetic failure\","
                + "\"backend_error_code\":\"E_SYNTHETIC\"}";
        boolean threw = false;
        try {
            MuseApi.parseFetchVms(Json.parse(err), 200);
        } catch (IOException e) {
            threw = true;
            check(e.getMessage() != null && e.getMessage().contains("E_SYNTHETIC"),
                    "error payload throws with backend_error_code");
        }
        check(threw, "error payload throws");

        System.out.println("FETCHVMS PARSE PASS (all)");
    }
}
