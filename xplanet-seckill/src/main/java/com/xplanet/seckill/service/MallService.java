package com.xplanet.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.seckill.domain.*;
import com.xplanet.seckill.dto.*;
import com.xplanet.seckill.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MallService {
    private final ProductMapper productMapper;
    private final CartItemMapper cartItemMapper;
    private final NormalOrderMapper normalOrderMapper;
    private final NormalOrderItemMapper normalOrderItemMapper;

    public List<Product> products(String keyword, String category) {
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1);
        if (keyword != null && !keyword.isBlank()) query.and(q -> q.like(Product::getName, keyword.trim()).or().like(Product::getSubtitle, keyword.trim()));
        if (category != null && !category.isBlank() && !"全部".equals(category)) query.eq(Product::getCategory, category);
        return productMapper.selectList(query.orderByDesc(Product::getSales).orderByAsc(Product::getId));
    }

    public Product product(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() != 1) throw new BizException(ErrorCode.NOT_FOUND);
        return product;
    }

    public CartVO cart(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));
        List<CartItemVO> views = items.stream().map(item -> toCartItem(item, productMapper.selectById(item.getProductId())))
                .filter(Objects::nonNull).collect(Collectors.toList());
        CartVO cart = new CartVO(); cart.setItems(views);
        cart.setTotalQuantity(views.stream().mapToInt(CartItemVO::getQuantity).sum());
        cart.setTotalAmount(views.stream().map(CartItemVO::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        return cart;
    }

    @Transactional
    public CartVO addCart(Long userId, Long productId, int quantity) {
        Product product = product(productId);
        if (quantity < 1 || quantity > 99) throw new BizException(1001, "商品数量应为 1-99");
        CartItem existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId).eq(CartItem::getProductId, productId));
        int target = quantity + (existing == null ? 0 : existing.getQuantity());
        if (target > product.getStock()) throw new BizException(3001, "库存不足");
        if (existing == null) {
            CartItem item = new CartItem(); item.setUserId(userId); item.setProductId(productId); item.setQuantity(quantity); cartItemMapper.insert(item);
        } else { existing.setQuantity(target); cartItemMapper.updateById(existing); }
        return cart(userId);
    }

    @Transactional
    public CartVO updateCart(Long userId, Long productId, int quantity) {
        CartItem item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId).eq(CartItem::getProductId, productId));
        if (item == null) throw new BizException(ErrorCode.NOT_FOUND);
        if (quantity <= 0) cartItemMapper.deleteById(item.getId());
        else {
            Product product = product(productId);
            if (quantity > product.getStock()) throw new BizException(3001, "库存不足");
            item.setQuantity(quantity); cartItemMapper.updateById(item);
        }
        return cart(userId);
    }

    @Transactional
    public OrderVO checkout(Long userId) {
        List<CartItem> cartItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));
        if (cartItems.isEmpty()) throw new BizException(3002, "购物车为空");
        List<Product> products = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem cartItem : cartItems) {
            Product product = product(cartItem.getProductId());
            if (productMapper.deductStock(product.getId(), cartItem.getQuantity()) != 1) throw new BizException(3001, product.getName() + " 库存不足");
            products.add(product);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }
        NormalOrder order = new NormalOrder(); order.setOrderNo(UUID.randomUUID().toString()); order.setUserId(userId); order.setTotalAmount(total); order.setStatus("PAID");
        normalOrderMapper.insert(order);
        for (int i = 0; i < cartItems.size(); i++) {
            CartItem cartItem = cartItems.get(i); Product product = products.get(i);
            NormalOrderItem item = new NormalOrderItem(); item.setOrderId(order.getId()); item.setProductId(product.getId()); item.setProductName(product.getName()); item.setPrice(product.getPrice()); item.setQuantity(cartItem.getQuantity());
            normalOrderItemMapper.insert(item);
            cartItemMapper.deleteById(cartItem.getId());
        }
        return toOrder(order);
    }

    public List<OrderVO> orders(Long userId) {
        return normalOrderMapper.selectList(new LambdaQueryWrapper<NormalOrder>().eq(NormalOrder::getUserId, userId).orderByDesc(NormalOrder::getId))
                .stream().map(this::toOrder).collect(Collectors.toList());
    }

    private CartItemVO toCartItem(CartItem item, Product product) {
        if (product == null || product.getStatus() != 1) return null;
        CartItemVO view = new CartItemVO(); view.setProductId(product.getId()); view.setName(product.getName()); view.setSubtitle(product.getSubtitle()); view.setCover(product.getCover());
        view.setPrice(product.getPrice()); view.setStock(product.getStock()); view.setQuantity(item.getQuantity()); view.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))); return view;
    }

    private OrderVO toOrder(NormalOrder order) {
        OrderVO view = new OrderVO(); view.setId(order.getId()); view.setOrderNo(order.getOrderNo()); view.setTotalAmount(order.getTotalAmount()); view.setStatus(order.getStatus()); view.setCreateTime(order.getCreateTime());
        view.setItems(normalOrderItemMapper.selectList(new LambdaQueryWrapper<NormalOrderItem>().eq(NormalOrderItem::getOrderId, order.getId())).stream().map(item -> {
            OrderItemVO row = new OrderItemVO(); row.setProductName(item.getProductName()); row.setPrice(item.getPrice()); row.setQuantity(item.getQuantity()); return row;
        }).collect(Collectors.toList())); return view;
    }
}
