package uz.yukgo.wallet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.PageResponse;
import uz.yukgo.wallet.WalletDtos.AdjustRequest;
import uz.yukgo.wallet.WalletDtos.AdminTxDto;
import uz.yukgo.wallet.WalletDtos.PricingDto;
import uz.yukgo.wallet.WalletDtos.SettingsRequest;

import java.util.Map;

/** Daromad paneli. /api/admin/** ga faqat ADMIN va OWNER kiradi; o'zgartirishlarni faqat OWNER qiladi (servisda). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminWalletController {
    private final WalletService wallet;

    @GetMapping("/monetization")
    public Map<String, Object> overview() {
        return wallet.adminOverview();
    }

    @PutMapping("/monetization")
    public PricingDto update(@Valid @RequestBody SettingsRequest req) {
        return wallet.updateSettings(req);
    }

    @GetMapping("/wallet/transactions")
    public PageResponse<AdminTxDto> transactions(@RequestParam(defaultValue = "0") int page) {
        return wallet.allTransactions(page);
    }

    @PostMapping("/wallet/adjust")
    public Map<String, Object> adjust(@Valid @RequestBody AdjustRequest req) {
        return wallet.adjust(req);
    }
}
