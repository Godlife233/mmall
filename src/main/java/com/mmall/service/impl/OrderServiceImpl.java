package com.mmall.service.impl;

import com.alipay.api.AlipayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.demo.trade.config.Configs;
import com.alipay.demo.trade.model.ExtendParams;
import com.alipay.demo.trade.model.GoodsDetail;
import com.alipay.demo.trade.model.builder.AlipayTradePrecreateRequestBuilder;
import com.alipay.demo.trade.model.result.AlipayF2FPrecreateResult;
import com.alipay.demo.trade.service.AlipayTradeService;
import com.alipay.demo.trade.service.impl.AlipayTradeServiceImpl;
import com.alipay.demo.trade.utils.ZxingUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.mmall.common.Const;
import com.mmall.common.ServerResponse;
import com.mmall.dao.*;
import com.mmall.pojo.*;
import com.mmall.service.IOrderService;
import com.mmall.util.BigDecimalUtil;
import com.mmall.util.DateTimeUtil;
import com.mmall.util.FTPUtil;
import com.mmall.util.PropertiesUtil;
import com.mmall.vo.OrderItemVO;
import com.mmall.vo.OrderProductVo;
import com.mmall.vo.OrderVO;
import com.mmall.vo.ShippingVo;
import org.apache.commons.lang.StringUtils;
import org.aspectj.weaver.ast.Or;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Service("iOrderService")
public class OrderServiceImpl implements IOrderService {
    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private PayInfoMapper payInfoMapper;

    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShippingMapper shippingMapper;
    @Autowired
    private CartMapper cartMapper;


    public ServerResponse getOrderCartProduct(Integer userId){
        OrderProductVo orderProductVo = new OrderProductVo();
        //从购物车中获取数据
        List<Cart> cartList = cartMapper.selectCheckedCartByUserId(userId);
        ServerResponse serverResponse= this.getCartOrderItem(userId,cartList);
        if(!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>) serverResponse.getData();

        List<OrderItemVO> orderItemVOList = Lists.newArrayList();

        BigDecimal payment = new BigDecimal("0");
        for(OrderItem orderItem:orderItemList){
            payment = BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
            orderItemVOList.add(assembleOrderItemVo(orderItem));
        }
        orderProductVo.setProductTotalPrice(payment);
        orderProductVo.setOrderItemVOList(orderItemVOList);
        orderProductVo.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));
        return ServerResponse.createBySuccess(orderProductVo);
    }

    public ServerResponse<OrderVO> getOrderDetail(Integer userId,Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order!=null){
            List<OrderItem> orderItemList = orderItemMapper.getByOrderNoUserId(orderNo,userId);
            OrderVO orderVO = assembleOrderVo(order,orderItemList);
            return ServerResponse.createBySuccess(orderVO);
        }
        return ServerResponse.createByErrorMessage("没有找到该订单");
    }


    public ServerResponse<String> cancel (Integer userId,Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null ){
            return ServerResponse.createByErrorMessage("该用户此订单不存在");
        }
        if(order.getStatus() != Const.OrderStatusEnum.NO_PAY.getCode()){
            return ServerResponse.createByErrorMessage("已付款，无法取消订单");
        }
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(Const.OrderStatusEnum.CANCELED.getCode());

        int rowCount = orderMapper.updateByPrimaryKeySelective(updateOrder);
        if(rowCount>0){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();

    }

    public ServerResponse createOrder(Integer userId,Integer shippingId){
        List<Cart> cartList = cartMapper.selectCheckedCartByUserId(userId);

        //计算总价
        ServerResponse serverResponse = this.getCartOrderItem(userId,cartList);
        if(!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>) serverResponse.getData();
        BigDecimal payment =this.getOrderTotalPrice(orderItemList);

        //生成订单
        Order order = this.assmableOrder(userId,shippingId,payment);
        if(order==null){
            return ServerResponse.createByErrorMessage("生成订单错误");
        }
        if(orderItemList.isEmpty()){
            return ServerResponse.createByErrorMessage("购物车为空");
        }

        for(OrderItem orderItem:orderItemList){
            orderItem.setOrderNo(order.getOrderNo());
        }
        //Mybatis 批量插入
        orderItemMapper.batchInsert(orderItemList);

        //减少产品库存
        this.reduceProductStock(orderItemList);

        //清空购物车
        this.cleanCart(cartList);

        //返回订单明细
        OrderVO orderVo=assembleOrderVo(order,orderItemList);
        return ServerResponse.createBySuccess(orderVo);
    }

    private OrderVO assembleOrderVo(Order order,List<OrderItem> orderItemList){
        OrderVO orderVO =new OrderVO();
        orderVO.setOrderNo(order.getOrderNo());
        orderVO.setPayment(order.getPayment());
        orderVO.setPaymentType(order.getPaymentType());
        orderVO.setPaymentTypeDesc(Const.PaymentTypeEnum.codeOf(order.getPaymentType()).getValue());

        orderVO.setPostage(order.getPostage());
        orderVO.setStatus(order.getStatus());
        orderVO.setStatusDesc(Const.OrderStatusEnum.codeOf(order.getStatus()).getValue());

        order.setShippingId(order.getShippingId());
        Shipping shipping = shippingMapper.selectByPrimaryKey(order.getShippingId());
        if(shipping!=null){
            orderVO.setReceiverName(shipping.getReceiverName());
            orderVO.setShippingVo(this.assembleShippingVo(shipping));

        }
        orderVO.setPaymentTime(DateTimeUtil.dateToStr(order.getPaymentTime()));
        orderVO.setCloseTime(DateTimeUtil.dateToStr(order.getCloseTime()));
        orderVO.setCreateTime(DateTimeUtil.dateToStr(order.getCreateTime()));
        orderVO.setEndTime(DateTimeUtil.dateToStr(order.getEndTime()));
        orderVO.setSendTime(DateTimeUtil.dateToStr(order.getSendTime()));

        orderVO.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));

        List<OrderItemVO> orderItemVOList = Lists.newArrayList();
        for(OrderItem orderItem:orderItemList){
            OrderItemVO  orderItemVO =this.assembleOrderItemVo(orderItem);
            orderItemVOList.add(orderItemVO);
        }

        orderVO.setOrderItemVOList(orderItemVOList);
        return orderVO;


    }

    private OrderItemVO assembleOrderItemVo(OrderItem orderItem){
        OrderItemVO orderItemVO = new OrderItemVO();
        orderItemVO.setCurrentUnitPrice(orderItem.getCurrentUnitPrice());
        orderItemVO.setOrderNo(orderItem.getOrderNo());
        orderItemVO.setProductId(orderItem.getProductId());
        orderItemVO.setProductImage(orderItem.getProductImage());
        orderItemVO.setProductName(orderItem.getProductName());
        orderItemVO.setTotalPrice(orderItem.getTotalPrice());
        orderItemVO.setQuantity(orderItem.getQuantity());

        orderItemVO.setCreateTime(DateTimeUtil.dateToStr(orderItem.getCreateTime()));
        return orderItemVO;






    }

    private ShippingVo assembleShippingVo(Shipping shipping){
        ShippingVo shippingVo = new ShippingVo();
        shippingVo.setReceiverName(shipping.getReceiverName());
        shippingVo.setReceiverAddress(shipping.getReceiverAddress());
        shippingVo.setReceiverProvince(shipping.getReceiverProvince());
        shippingVo.setReceiverCity(shipping.getReceiverCity());
        shippingVo.setReceiverDistrict(shipping.getReceiverDistrict());
        shippingVo.setReceiverZip(shipping.getReceiverZip());
        shippingVo.setReceiverPhone(shipping.getReceiverPhone());
        return shippingVo;
    }

    private void cleanCart(List<Cart> cartList){
        for(Cart cart:cartList){
            cartMapper.deleteByPrimaryKey(cart.getId());
        }
    }

    private void  reduceProductStock(List<OrderItem> orderItemList){
        for(OrderItem orderItem:orderItemList){
            Product product = productMapper.selectByPrimaryKey(orderItem.getProductId());
            product.setStock(product.getStock()-orderItem.getQuantity());
            productMapper.updateByPrimaryKey(product);
        }
    }

    private Order assmableOrder(Integer userId,Integer shippingId,BigDecimal payment){
        Order order = new Order();
        Long orderNo = this.generateOrderNo();
        order.setOrderNo(orderNo);
        order.setStatus(Const.OrderStatusEnum.NO_PAY.getCode());
        order.setPostage(0);
        order.setPaymentType(Const.PaymentTypeEnum.ONLINE_PAY.getCode());
        order.setPayment(payment);

        order.setUserId(userId);
        order.setShippingId(shippingId);

       int rowCount = orderMapper.insert(order);
       if(rowCount>0){
           return order;
       }
       return  null;

    }

    private Long generateOrderNo(){
        long currentTime =System.currentTimeMillis();
        return currentTime+new Random().nextInt(100);
    }

    private BigDecimal getOrderTotalPrice( List<OrderItem> orderItemList){
        BigDecimal payment = new BigDecimal("0");
        for(OrderItem orderItem:orderItemList){
           payment= BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
        }

        return payment;
    }

    private ServerResponse getCartOrderItem (Integer userId,List<Cart> cartList){
        List<OrderItem> orderItemList = Lists.newArrayList();
        if(CollectionUtils.isEmpty(cartList)){
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        for(Cart cart:cartList){
            OrderItem orderItem = new OrderItem();
            Product product = productMapper.selectByPrimaryKey(cart.getProductId());
            if(Const.ProductStatusEnum.ON_SALE.getCode()!=product.getStatus()){
                return ServerResponse.createByErrorMessage("产品:"+product.getName()+"不是在线售卖状态");
            }
            if(cart.getQuantity()>product.getStock()){
                return ServerResponse.createByErrorMessage("产品:"+product.getName()+"库存不足");
            }
            orderItem.setUserId(userId);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            orderItem.setCurrentUnitPrice(product.getPrice());
            orderItem.setQuantity(cart.getQuantity());
            orderItem.setTotalPrice(BigDecimalUtil.mul(product.getPrice().doubleValue(),cart.getQuantity().doubleValue()));
            orderItemList.add(orderItem);

        }

        return ServerResponse.createBySuccess(orderItemList);
    }

    private static AlipayTradeService tradeService;

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    public ServerResponse pay(Long orderNo,Integer userId,String path){


        Map<String,String> resultMap = new HashMap();
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order==null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        resultMap.put("orderNo",String.valueOf(order.getOrderNo()));





        /** 一定要在创建AlipayTradeService之前调用Configs.init()设置默认参数
         *  Configs会读取classpath下的zfbinfo.properties文件配置信息，如果找不到该文件则确认该文件是否在classpath目录
         */
        Configs.init("zfbinfo.properties");

        /** 使用Configs提供的默认参数
         *  AlipayTradeService可以使用单例或者为静态成员对象，不需要反复new
         */
        tradeService = new AlipayTradeServiceImpl.ClientBuilder().build();


        // (必填) 商户网站订单系统中唯一订单号，64个字符以内，只能包含字母、数字、下划线，
        // 需保证商户系统端不能重复，建议通过数据库sequence生成，
        String outTradeNo = order.getOrderNo().toString();

        // (必填) 订单标题，粗略描述用户的支付目的。如“xxx品牌xxx门店消费”
        String subject = new StringBuilder().append("happymmall扫码支付，订单号：").append(outTradeNo).toString();

        // (必填) 订单总金额，单位为元，不能超过1亿元
        // 如果同时传入了【打折金额】,【不可打折金额】,【订单总金额】三者,则必须满足如下条件:【订单总金额】=【打折金额】+【不可打折金额】
        String totalAmount = order.getPayment().toString();

        // (可选) 订单不可打折金额，可以配合商家平台配置折扣活动，如果酒水不参与打折，则将对应金额填写至此字段
        // 如果该值未传入,但传入了【订单总金额】,【打折金额】,则该值默认为【订单总金额】-【打折金额】
        String undiscountableAmount = "0";

        // 卖家支付宝账号ID，用于支持一个签约账号下支持打款到不同的收款账号，(打款到sellerId对应的支付宝账号)
        // 如果该字段为空，则默认为与支付宝签约的商户的PID，也就是appid对应的PID
        String sellerId = "";

        // 订单描述，可以对交易或商品进行一个详细地描述，比如填写"购买商品2件共15.00元"
        String body = new StringBuilder().append("订单").append(outTradeNo).append("购买商品共").append(totalAmount).append("元").toString();

        // 商户操作员编号，添加此参数可以为商户操作员做销售统计
        String operatorId = "test_operator_id";

        // (必填) 商户门店编号，通过门店号和商家后台可以配置精准到门店的折扣信息，详询支付宝技术支持
        String storeId = "test_store_id";

        // 业务扩展参数，目前可添加由支付宝分配的系统商编号(通过setSysServiceProviderId方法)，详情请咨询支付宝技术支持
        ExtendParams extendParams = new ExtendParams();
        extendParams.setSysServiceProviderId("2088100200300400500");

        // 支付超时，定义为120分钟
        String timeoutExpress = "120m";

        // 商品明细列表，需填写购买商品详细信息，
        List<GoodsDetail> goodsDetailList = new ArrayList<GoodsDetail>();

        List<OrderItem> orderItemsList =orderItemMapper.getByOrderNoUserId(orderNo,userId);
        for(OrderItem orderItem:orderItemsList){
            GoodsDetail goods1 = GoodsDetail.newInstance(orderItem.getProductId().toString(), orderItem.getProductName(), BigDecimalUtil.mul(orderItem.getCurrentUnitPrice().doubleValue(),new Double(100).doubleValue()).longValue()
                    , orderItem.getQuantity());
            goodsDetailList.add(goods1);
        }



        // 创建扫码支付请求builder，设置请求参数
        AlipayTradePrecreateRequestBuilder builder = new AlipayTradePrecreateRequestBuilder()
                .setSubject(subject).setTotalAmount(totalAmount).setOutTradeNo(outTradeNo)
                .setUndiscountableAmount(undiscountableAmount).setSellerId(sellerId).setBody(body)
                .setOperatorId(operatorId).setStoreId(storeId).setExtendParams(extendParams)
                .setTimeoutExpress(timeoutExpress)
                               .setNotifyUrl(PropertiesUtil.getProperty("alipay.callback.url"))//支付宝服务器主动通知商户服务器里指定的页面http路径,根据需要设置
                .setGoodsDetailList(goodsDetailList);

        AlipayF2FPrecreateResult result = tradeService.tradePrecreate(builder);
        switch (result.getTradeStatus()) {
            case SUCCESS:
                log.info("支付宝预下单成功: )");

                AlipayTradePrecreateResponse response = result.getResponse();
                dumpResponse(response);
                //
                File folder = new File(path);
                if(!folder.exists()){
                    folder.setWritable(true);
                    folder.mkdirs();
                }


                // 需要修改为运行机器上的路径
                String qrPath = String.format(path+"/qr-%s.png",
                        response.getOutTradeNo());
                String qrFileName = String.format("qr-%s.png",response.getOutTradeNo());
                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, qrPath);

                File targetFile = new File(path,qrFileName);
                try {
                    FTPUtil.uploadFile(Lists.newArrayList(targetFile));
                } catch (IOException e) {
                    log.error("上传二维码异常",e);
                    e.printStackTrace();
                }
                log.info("filePath:" + qrPath);

                String[] targetPaths = qrPath.split("\\\\");
                String targetPath = targetPaths[5];

                String qrUrl = PropertiesUtil.getProperty("ftp.server.http.prefix")+targetPath;
                resultMap.put("qrUrl",qrUrl);
                return  ServerResponse.createBySuccess(resultMap);







                //                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, filePath);


            case FAILED:
                log.error("支付宝预下单失败!!!");
                return ServerResponse.createByErrorMessage("支付宝预下单失败!!!");

            case UNKNOWN:
                log.error("系统异常，预下单状态未知!!!");
                return ServerResponse.createByErrorMessage("系统异常，预下单状态未知!!!");

            default:
                log.error("不支持的交易状态，交易返回异常!!!");
                return ServerResponse.createByErrorMessage("不支持的交易状态，交易返回异常!!!");
        }




    }

    private void dumpResponse(AlipayResponse response) {
        if (response != null) {
            log.info(String.format("code:%s, msg:%s", response.getCode(), response.getMsg()));
            if (StringUtils.isNotEmpty(response.getSubCode())) {
                log.info(String.format("subCode:%s, subMsg:%s", response.getSubCode(),
                        response.getSubMsg()));

            }
            log.info("body:" + response.getBody());
        }
    }

    public  ServerResponse aliCallback(Map<String,String> params){
       Long orderNo = Long.parseLong(params.get("out_trade_no"));
       String tradeNo = params.get("trade_no");
       String tradeStatus = params.get("trade_status");

       Order order =orderMapper.selectByOrderNo(orderNo);
       if(order==null){
           return ServerResponse.createByErrorMessage("订单错误，回调忽略");
       }
       if(order.getStatus()>= Const.OrderStatusEnum.PAID.getCode()){
           return ServerResponse.createBySuccess("支付宝重复调用");
       }
       if(Const.AlipayCallback.TRADE_STATUS_TRADE_SUCCESS.equals(tradeStatus)){
           order.setPaymentTime(DateTimeUtil.strToDate(params.get("gmt_payment")));
           order.setStatus(Const.OrderStatusEnum.PAID.getCode());
           orderMapper.updateByPrimaryKey(order);
       }

        PayInfo payInfo = new PayInfo();
       payInfo.setUserId(order.getUserId());
       payInfo.setOrderNo(order.getOrderNo());
       payInfo.setPayPlatform(Const.PayPlatformRnum.ALIPAY.getCode());
       payInfo.setPlatformNumber(tradeNo);
       payInfo.setPlatformStatus(tradeStatus);

       payInfoMapper.insert(payInfo);

       return ServerResponse.createBySuccess();


    }

    public ServerResponse queryOrderPayStatus(Integer userId,Long orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order == null ){
            return  ServerResponse.createByErrorMessage("没有该订单");
        }
        if(order.getStatus()>= Const.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }

    public Boolean checkOrder(Long orderNo, BigDecimal payment){
       Boolean result = false ;
       Order order = orderMapper.selectByOrderNoAndPayment(orderNo,payment);
       if(order!=null){
           result = true;
       }
       return  result;
    }

    public ServerResponse<PageInfo> getOrderList(Integer userId,int pageNum,int pageSize){
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectByUserId(userId);
        List<OrderVO> orderVOList = this.assembleOrderVoList(userId,orderList);
        PageInfo pageInfo = new PageInfo(orderList);
        pageInfo.setList(orderVOList);
        return ServerResponse.createBySuccess(pageInfo);

    }

    private List<OrderVO> assembleOrderVoList(Integer userId,List<Order> orderList){
        List<OrderVO> orderVOList = Lists.newArrayList();
        for(Order order:orderList){
            List<OrderItem> orderItemList = Lists.newArrayList();
            if(userId ==null){
                //管理员查询时不需要传userId
                orderItemList = orderItemMapper.getOrderByOrderNo(order.getOrderNo());
            }else{
                orderItemList = orderItemMapper.getByOrderNoUserId(order.getOrderNo(),userId);
            }
            OrderVO  orderVO = assembleOrderVo(order,orderItemList);
            orderVOList.add(orderVO);

        }
        return orderVOList;
    }









    //backend

    public ServerResponse<PageInfo> manageList(int pageNum,int pageSize){
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectAllOrder();
        List<OrderVO> orderVOList = this.assembleOrderVoList(null,orderList);
        PageInfo pageInfo = new PageInfo(orderList);
        pageInfo.setList(orderVOList);

        return ServerResponse.createBySuccess(pageInfo);

    }

    public ServerResponse<OrderVO> manageDetail(Long orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order!=null){
            List <OrderItem> orderItemList = orderItemMapper.getOrderByOrderNo(orderNo);
            OrderVO orderVO = assembleOrderVo(order,orderItemList);
            return ServerResponse.createBySuccess(orderVO);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    public ServerResponse<PageInfo> manageSearch(Long orderNo,int pageNum,int pageSize){
        PageHelper.startPage(pageNum,pageSize);
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order!=null){
            List <OrderItem> orderItemList = orderItemMapper.getOrderByOrderNo(orderNo);
            OrderVO orderVO = assembleOrderVo(order,orderItemList);
            PageInfo pageInfo = new PageInfo(Lists.newArrayList(order));
            pageInfo.setList(Lists.newArrayList(orderVO));
            return ServerResponse.createBySuccess(pageInfo);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    public ServerResponse<String> manageSendGoods(Long orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order!=null){
            if(order.getStatus() == Const.OrderStatusEnum.PAID.getCode()){
                order.setStatus(Const.OrderStatusEnum.SHIPPED.getCode());
                order.setSendTime(new Date());
                orderMapper.updateByPrimaryKey(order);
                return ServerResponse.createBySuccess("发货成功");
            }
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }



}
