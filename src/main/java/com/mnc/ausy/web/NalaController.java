package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.NalaUploadService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Controller
public class NalaController {
    private final NalaUploadService nala;
    public NalaController(NalaUploadService nala) { this.nala = nala; }

    @GetMapping("/nala")
    public String page() { return "nala/index"; }

    @PostMapping("/nala/init")
    @ResponseBody
    public Map<String,Object> init(@RequestParam String originalName, @RequestParam long fileSize,
                                   @RequestParam int totalChunks, @RequestParam(required=false, defaultValue="") String checksum,
                                   @AuthenticationPrincipal AusyUserDetails me) throws Exception {
        return nala.init(me.getUsername(), originalName, fileSize, totalChunks, checksum);
    }

    @PostMapping("/nala/chunk")
    @ResponseBody
    public Map<String,Object> chunk(@RequestParam String uploadId, @RequestParam int chunkIndex,
                                    @RequestParam("chunk") MultipartFile chunk) throws Exception {
        return nala.receiveChunk(uploadId, chunkIndex, chunk.getBytes());
    }

    @GetMapping("/nala/status")
    @ResponseBody
    public Map<String,Object> status(@RequestParam String uploadId) {
        return nala.status(uploadId);
    }

    @PostMapping("/nala/finalize")
    @ResponseBody
    public Map<String,Object> finalize(@RequestParam String uploadId) throws Exception {
        Map<String,Object> r = nala.finalizeUpload(uploadId);
        if (Boolean.TRUE.equals(r.get("ok")) && !Boolean.TRUE.equals(r.get("already_done"))) {
            nala.processAsync(uploadId);   // async backend processing
        }
        return r;
    }
}
