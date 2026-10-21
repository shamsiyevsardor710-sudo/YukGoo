package uz.yukgo.wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.PageResponse;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.wallet.WalletDtos.PricingDto;
import uz.yukgo.wallet.WalletDtos.TxDto;
import uz.yukgo.wallet.WalletDtos.WalletDto;

/** Foydalanuvchining o'z balansi va tarixi */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService wallet;
    private final CurrentUser currentUser;

    @GetMapping
    public WalletDto me() {
        return new WalletDto(wallet.balance(currentUser.id()), PricingDto.of(wallet.current()));
    }

    @GetMapping("/transactions")
    public PageResponse<TxDto> transactions(@RequestParam(defaultValue = "0") int page) {
        return wallet.history(currentUser.id(), page);
    }
}
