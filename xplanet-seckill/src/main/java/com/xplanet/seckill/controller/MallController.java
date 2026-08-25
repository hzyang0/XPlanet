package com.xplanet.seckill.controller;

import com.xplanet.common.auth.UserContext;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import com.xplanet.seckill.domain.Product;
import com.xplanet.seckill.dto.CartVO;
import com.xplanet.seckill.dto.OrderVO;
import com.xplanet.seckill.service.MallService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

@RestController
@RequestMapping("/api/mall")
@RequiredArgsConstructor
public class MallController {
    private final MallService mallService;

    @GetMapping("/products") public R<List<Product>> products(@RequestParam(required = false) String keyword, @RequestParam(required = false) String category) { return R.ok(mallService.products(keyword, category)); }
    @GetMapping("/products/{productId}") public R<Product> product(@PathVariable Long productId) { return R.ok(mallService.product(productId)); }
    @GetMapping("/cart") public R<CartVO> cart() { return R.ok(mallService.cart(userId())); }
    @PostMapping("/cart") public R<CartVO> addCart(@Valid @RequestBody CartCommand command) { return R.ok(mallService.addCart(userId(), command.getProductId(), command.getQuantity())); }
    @PutMapping("/cart/{productId}") public R<CartVO> updateCart(@PathVariable Long productId, @Valid @RequestBody QuantityCommand command) { return R.ok(mallService.updateCart(userId(), productId, command.getQuantity())); }
    @DeleteMapping("/cart/{productId}") public R<CartVO> removeCart(@PathVariable Long productId) { return R.ok(mallService.updateCart(userId(), productId, 0)); }
    @PostMapping("/orders/checkout") public R<OrderVO> checkout() { return R.ok(mallService.checkout(userId())); }
    @GetMapping("/orders") public R<List<OrderVO>> orders() { return R.ok(mallService.orders(userId())); }

    private Long userId() { if (UserContext.getUserId() == null) throw new BizException(ErrorCode.USER_NOT_LOGIN); return UserContext.getUserId(); }
    @Data public static class CartCommand { @NotNull private Long productId; @Min(1) @Max(99) private Integer quantity; }
    @Data public static class QuantityCommand { @Min(0) @Max(99) private Integer quantity; }
}
