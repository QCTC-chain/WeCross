# Stub 插件开发

## 一、Stub 设计

## 二、Stub 开发

### 2.1 系统合约

系统合约包括代理合约(WeCrossProxy)和桥接合约(WeCrossHub)，代理合约是WeCross调用该链其它合约的统一入口，桥接合约用于记录跨链调用请求，以配合跨链路由实现合约跨链调用

#### 2.1.1 代理合约

功能点：

- 合约调用入口
- 事务管理

- solidity 版本

  ```solidity
  /*
  *   v1.0.0
  *   proxy contract for WeCross
  *   main entrance of all contract call
  */
  
  pragma solidity >=0.5.0 <0.6.0;
  pragma experimental ABIEncoderV2;
  
  contract WeCrossProxy {
  
      string constant version = "v1.0.0";
  
      // per step of xa transaction
      struct XATransactionStep {
          string accountIdentity;
          uint256 timestamp;
          string path;
          address contractAddress;
          string func;
          bytes args;
      }
  
      // information of xa transaction
      struct XATransaction {
          string accountIdentity;
          string[] paths;     // all paths related to this transaction
          address[] contractAddresses; // locked addressed in current chain
          string status;      // processing | committed |  rolledback
          uint256 startTimestamp;
          uint256 commitTimestamp;
          uint256 rollbackTimestamp;
          uint256[] seqs;    // sequence of each step
          uint256 stepNum;   // step number
      }
  
      struct ContractStatus {
          bool locked;     // isolation control, read-committed
          string xaTransactionID;
      }
  
      mapping(address => ContractStatus) lockedContracts;
  
      mapping(string => XATransaction) xaTransactions;      // key: xaTransactionID
  
      mapping(string => XATransactionStep) xaTransactionSteps;  // key: xaTransactionID || xaTransactionSeq
  
      /*
      * record all xa transactionIDs
      * head: point to the current xa transaction to be checked
      * tail: point to the next position for added xa transaction
      */
      uint256 head = 0;
      uint256 tail = 0;
      string[] xaTransactionIDs;
  
      string constant XA_STATUS_PROCESSING = "processing";
      string constant XA_STATUS_COMMITTED = "committed";
      string constant XA_STATUS_ROLLEDBACK = "rolledback";
  
      string constant REVERT_FLAG = "_revert";
      string constant NULL_FLAG = "null";
      string constant SUCCESS_FLAG = "success";
  
      byte   constant SEPARATOR = '.';
  
      uint256 constant ADDRESS_LEN = 42;
      uint256 constant MAX_SETP = 1024;
  
      string[] pathCache;
  
      struct Transaction {
          bool existed;
          bytes result;
      }
  
      mapping(string => Transaction) transactions; // key: uniqueID
  
      CNSPrecompiled cns;
      constructor() public {
          cns = CNSPrecompiled(0x1004);
      }
  
      function getVersion() public pure
      returns(string memory)
      {
          return version;
      }
  
      function addPath(string memory _path) public
      {
          pathCache.push(_path);
      }
  
      function getPaths() public view
      returns (string[] memory)
      {
          return pathCache;
      }
  
      function deletePathList() public
      {
          pathCache.length = 0;
      }
  
      /*
      * deploy contract by contract binary code
      */
      function deployContract(bytes memory _bin) public returns(address addr) {
          bool ok = false;
          assembly {
              addr := create(0,add(_bin,0x20), mload(_bin))
              ok := gt(extcodesize(addr),0)
          }
          if(!ok) {
              revert("deploy contract failed");
          }
      }
  
      /**
      * deploy contract and register contract to cns
      */
      function deployContractWithRegisterCNS(string memory _path, string memory _version, bytes memory _bin, string memory _abi) public returns(address) {
          string memory name = getNameByPath(_path);
          address addr = getAddressByName(name, false);
          if((addr != address(0x0))  && lockedContracts[addr].locked) {
              revert(string(abi.encodePacked(name, " is locked by unfinished xa transaction: ", lockedContracts[addr].xaTransactionID)));
          }
  
          // deploy contract first
          address deploy_addr = deployContract(_bin);
          // register to cns
          int ret = cns.insert(name, _version, addressToString(deploy_addr), _abi);
          if(1 != ret) {
              revert(string(abi.encodePacked(name, ":", _version, " unable register to cns, error: ", uint256ToString(uint256(ret > 0? ret : -ret)))));
          }
          pathCache.push(_path);
          return deploy_addr;
      }
  
      /**
      * register contract to cns
      */
      function registerCNS(string memory _path, string memory _version, string memory _addr, string memory _abi) public {
          string memory name = getNameByPath(_path);
          address addr = getAddressByName(name, false);
          if((addr != address(0x0))  && lockedContracts[addr].locked) {
              revert(string(abi.encodePacked(name, " is locked by unfinished xa transaction: ", lockedContracts[addr].xaTransactionID)));
          }
  
          // check if version info exist ???
          int ret = cns.insert(name, _version, _addr, _abi);
          if(1 != ret) {
              revert(string(abi.encodePacked(name, ":", _version, " unable register to cns, error: ", uint256ToString(uint256(ret > 0 ? ret : - ret)))));
          }
          pathCache.push(_path);
      }
  
      /**
      * select cns by name
      */
      function selectByName(string memory _name) public view returns(string memory) {
          return cns.selectByName(_name);
      }
  
      /**
      * select cns by name and version
      */
      function selectByNameAndVersion(string memory _name, string memory _version) public view returns(string memory) {
          return cns.selectByNameAndVersion(_name, _version);
      }
  
      // constant call with xaTransactionID
      function constantCall(string memory _XATransactionID, string memory _path, string memory _func, bytes memory _args) public
      returns(bytes memory)
      {
          address addr = getAddressByPath(_path);
  
          if(!isExistedXATransaction(_XATransactionID)) {
              revert("xa transaction not found");
          }
  
          if(!sameString(lockedContracts[addr].xaTransactionID, _XATransactionID)) {
              revert(string(abi.encodePacked(_path, " is unregistered in xa transaction: ", _XATransactionID)));
          }
  
          return callContract(addr, _func, _args);
      }
  
      // constant call without xaTransactionID
      function constantCall(string memory _name, bytes memory _argsWithMethodId) public
      returns(bytes memory)
      {
          // find address from abi cache first
          address addr = getAddressByName(_name, true);
  
          if(lockedContracts[addr].locked) {
              revert(string(abi.encodePacked("resource is locked by unfinished xa transaction: ", lockedContracts[addr].xaTransactionID)));
          }
  
          return callContract(addr, _argsWithMethodId);
      }
  
      // non-constant call with xaTransactionID
      function sendTransaction(string memory _uid, string memory _XATransactionID, uint256 _XATransactionSeq, string memory _path, string memory _func, bytes memory _args) public
      returns(bytes memory)
      {
          if(transactions[_uid].existed) {
              return transactions[_uid].result;
          }
  
          address addr = getAddressByPath(_path);
  
          if(!isExistedXATransaction(_XATransactionID)) {
              revert("xa transaction not found");
          }
  
          if(sameString(xaTransactions[_XATransactionID].status, XA_STATUS_COMMITTED)) {
              revert("xa transaction has been committed");
          }
  
          if(sameString(xaTransactions[_XATransactionID].status, XA_STATUS_ROLLEDBACK)) {
              revert("xa transaction has been rolledback");
          }
  
          if(!sameString(lockedContracts[addr].xaTransactionID, _XATransactionID)) {
              revert(string(abi.encodePacked(_path, " is unregistered in xa transaction ", _XATransactionID)));
          }
  
          if(!isValidXATransactionSep(_XATransactionID, _XATransactionSeq)) {
              revert("seq should be greater than before");
          }
  
          // recode step
          xaTransactionSteps[getXATransactionStepKey(_XATransactionID, _XATransactionSeq)] = XATransactionStep(
              addressToString(tx.origin),
              block.timestamp / 1000,
              _path,
              addr,
              _func,
              _args
          );
  
          // recode seq
          uint256 num = xaTransactions[_XATransactionID].stepNum;
          xaTransactions[_XATransactionID].seqs[num] = _XATransactionSeq;
          xaTransactions[_XATransactionID].stepNum = num + 1;
  
          bytes memory result =  callContract(addr, _func, _args);
  
          // recode transaction
          transactions[_uid] = Transaction(true, result);
          return result;
      }
  
      // non-constant call without xaTransactionID
      function sendTransaction(string memory _uid, string memory _name, bytes memory _argsWithMethodId) public returns(bytes memory) {
          if(transactions[_uid].existed) {
              return transactions[_uid].result;
          }
  
          // find address from abi cache first
          address addr = getAddressByName(_name, true);
  
          if(lockedContracts[addr].locked) {
              revert(string(abi.encodePacked(_name, " is locked by unfinished xa transaction: ", lockedContracts[addr].xaTransactionID)));
          }
  
          bytes memory result = callContract(addr, _argsWithMethodId);
  
          // recode transaction
          transactions[_uid] = Transaction(true, result);
          return result;
      }
  
      /*
      * @param xaTransactionID
      * @param selfPaths are related to current chain
      * result: success
      */
      function startXATransaction(string memory _xaTransactionID, string[] memory _selfPaths, string[] memory _otherPaths) public
      returns(string memory)
      {
          if(isExistedXATransaction(_xaTransactionID)) {
              revert(string(abi.encodePacked("xa transaction ", _xaTransactionID, " already exists")));
          }
  
          uint256 selfLen = _selfPaths.length;
          uint256 otherLen = _otherPaths.length;
  
          address[] memory contracts = new address[](selfLen);
          string[] memory allPaths = new string[](selfLen + otherLen);
  
          // recode ACL
          for(uint256 i = 0; i < selfLen; i++) {
              address addr = getAddressByPath(_selfPaths[i]);
              contracts[i] = addr;
              if(lockedContracts[addr].locked) {
                  revert(string(abi.encodePacked(_selfPaths[i], " is locked by unfinished xa transaction: ", lockedContracts[addr].xaTransactionID)));
              }
              lockedContracts[addr].locked = true;
              lockedContracts[addr].xaTransactionID = _xaTransactionID;
              allPaths[i] = _selfPaths[i];
          }
  
          for(uint256 i = 0; i < otherLen; i++)
          {
              allPaths[selfLen+i] = _otherPaths[i];
          }
  
          uint256[] memory seqs = new uint256[](MAX_SETP);
          // recode xa transaction
          xaTransactions[_xaTransactionID] = XATransaction(
              addressToString(tx.origin),
              allPaths,
              contracts,
              XA_STATUS_PROCESSING,
              block.timestamp / 1000,
              0,
              0,
              seqs,
              0
          );
  
          addXATransaction(_xaTransactionID);
  
          return SUCCESS_FLAG;
      }
  
      /*
      *  @param xaTransactionID
      * result: success
      */
      function commitXATransaction(string memory _xaTransactionID) public
      returns(string memory)
      {
          if(!isExistedXATransaction(_xaTransactionID)) {
              revert("xa transaction not found");
          }
  
          // has committed
          if(sameString(xaTransactions[_xaTransactionID].status, XA_STATUS_COMMITTED)) {
              revert("xa transaction has been committed");
          }
  
          // has rolledback
          if(sameString(xaTransactions[_xaTransactionID].status, XA_STATUS_ROLLEDBACK)) {
              revert("xa transaction has been rolledback");
          }
  
          xaTransactions[_xaTransactionID].commitTimestamp = block.timestamp / 1000;
          xaTransactions[_xaTransactionID].status = XA_STATUS_COMMITTED;
          deleteLockedContracts(_xaTransactionID);
  
          return SUCCESS_FLAG;
      }
  
      /*
      *  @param xaTransactionID
      * result: success | message
      */
      function rollbackXATransaction(string memory _xaTransactionID) public
      returns(string memory)
      {
          string memory result = SUCCESS_FLAG;
          if(!isExistedXATransaction(_xaTransactionID)) {
              revert("xa transaction not found");
          }
  
          // has committed
          if(sameString(xaTransactions[_xaTransactionID].status, XA_STATUS_COMMITTED)) {
              revert("xa transaction has been committed");
          }
  
          // has rolledback
          if(sameString(xaTransactions[_xaTransactionID].status, XA_STATUS_ROLLEDBACK)) {
              revert("xa transaction has been rolledback");
          }
  
          string memory message = 'warning:';
          uint256 stepNum = xaTransactions[_xaTransactionID].stepNum;
          for(uint256 i = stepNum; i > 0; i--) {
              uint256 seq = xaTransactions[_xaTransactionID].seqs[i-1];
              string memory key = getXATransactionStepKey(_xaTransactionID, seq);
  
              string memory func = xaTransactionSteps[key].func;
              address contractAddress = xaTransactionSteps[key].contractAddress;
              bytes memory args = xaTransactionSteps[key].args;
  
              // call revert function
              bytes memory sig = abi.encodeWithSignature(getRevertFunc(func, REVERT_FLAG));
              bool success;
              (success, ) = address(contractAddress).call(abi.encodePacked(sig, args));
              if(!success) {
                  message = string(abi.encodePacked(message, ' revert "', func, '" failed.'));
                  result = message;
              }
          }
  
          xaTransactions[_xaTransactionID].rollbackTimestamp = block.timestamp / 1000;
          xaTransactions[_xaTransactionID].status = XA_STATUS_ROLLEDBACK;
          deleteLockedContracts(_xaTransactionID);
          return result;
      }
  
      function getXATransactionNumber() public view
      returns (string memory)
      {
          if(xaTransactionIDs.length == 0) {
              return "0";
          } else {
              return uint256ToString(xaTransactionIDs.length);
          }
      }
  
      /*
      * traverse in reverse order
      * outputs:
      {
          "total": 100,
          "xaTransactions":
          [
              {
              	"xaTransactionID": "001",
          		"accountIdentity": "0x11",
          		"status": "processing",
          		"timestamp": 123,
          		"paths": ["a.b.1","a.b.2"]
          	},
          	{
              	"xaTransactionID": "002",
          		"accountIdentity": "0x11",
          		"status": "committed",
          		"timestamp": 123,
          		"paths": ["a.b.1","a.b.2"]
          	}
          ]
      }
      */
      function listXATransactions(string memory _index, uint256 _size) public view
      returns (string memory)
      {
          uint256 len = xaTransactionIDs.length;
          if (len == 0) {
              return '{"total":0,"xaTransactions":[]}';
          }
  
          uint256 index = sameString("-1", _index) ? (len - 1) : stringToUint256(_index);
  
          if (len <= index) {
              return '{"total":0,"xaTransactions":[]}';
          }
  
          string memory jsonStr = '[';
          for(uint256 i = 0; i < (_size - 1) && (index - i) > 0; i++) {
              string memory xaTransactionID = xaTransactionIDs[index-i];
              jsonStr = string(abi.encodePacked(jsonStr, '{"xaTransactionID":"', xaTransactionID, '",',
                  '"accountIdentity":"', xaTransactions[xaTransactionID].accountIdentity, '",',
                  '"status":"', xaTransactions[xaTransactionID].status, '",',
                  '"paths":', pathsToJson(xaTransactionID), ',',
                  '"timestamp":', uint256ToString(xaTransactions[xaTransactionID].startTimestamp), '},')
              );
          }
  
          uint256 lastIndex = (index + 1) >= _size ? (index + 1 - _size) : 0;
          string memory xaTransactionID = xaTransactionIDs[lastIndex];
          jsonStr = string(abi.encodePacked(jsonStr, '{"xaTransactionID":"', xaTransactionID, '",',
              '"accountIdentity":"', xaTransactions[xaTransactionID].accountIdentity, '",',
              '"status":"', xaTransactions[xaTransactionID].status, '",',
              '"paths":', pathsToJson(xaTransactionID), ',',
              '"timestamp":', uint256ToString(xaTransactions[xaTransactionID].startTimestamp), '}]')
          );
  
          return string(abi.encodePacked('{"total":', uint256ToString(len),',"xaTransactions":', jsonStr, '}'));
      }
  
      /*
      *  @param xaTransactionID
      * result with json form
      * example:
      {
      	"xaTransactionID": "1",
      	"accountIdentity": "0x88",
      	"status": "processing",
      	"paths":["a.b.c1","a.b.c2","a.b1.c3"],
      	"startTimestamp": 123,
      	"commitTimestamp": 456,
      	"rollbackTimestamp": 0,
      	"xaTransactionSteps": [{
      	        "accountIdentity":"0x12",
              	"xaTransactionSeq": 233,
      			"path": "a.b.c1",
      			"timestamp": 233,
      			"method": "set",
      			"args": "0010101"
      		},
      		{
      		    "accountIdentity":"0x12",
      		    "xaTransactionSeq": 244,
      			"path": "a.b.c2",
      			"timestamp": 244,
      			"method": "set",
      			"args": "0010101"
      		}
      	]
      }
      */
      function getXATransaction(string memory _xaTransactionID) public view
      returns(string memory)
      {
          if(!isExistedXATransaction(_xaTransactionID)) {
              revert("xa transaction not found");
          }
  
          return string(abi.encodePacked('{"xaTransactionID":"', _xaTransactionID, '",',
              '"accountIdentity":"', xaTransactions[_xaTransactionID].accountIdentity, '",',
              '"status":"', xaTransactions[_xaTransactionID].status, '",',
              '"paths":', pathsToJson(_xaTransactionID), ',',
              '"startTimestamp":', uint256ToString(xaTransactions[_xaTransactionID].startTimestamp), ',',
              '"commitTimestamp":', uint256ToString(xaTransactions[_xaTransactionID].commitTimestamp), ',',
              '"rollbackTimestamp":', uint256ToString(xaTransactions[_xaTransactionID].rollbackTimestamp), ',',
              '"xaTransactionSteps":', xaTransactionStepArrayToJson(_xaTransactionID, xaTransactions[_xaTransactionID].seqs, xaTransactions[_xaTransactionID].stepNum), "}")
          );
      }
  
      // called by router to check xa transaction status
      function getLatestXATransaction() public view
      returns(string memory)
      {
          string memory xaTransactionID;
          if(head == tail) {
              return '{}';
          } else {
              xaTransactionID = xaTransactionIDs[uint256(head)];
          }
          return getXATransaction(xaTransactionID);
      }
  
      // called by router to rollbach transaction
      function rollbackAndDeleteXATransactionTask(string memory _xaTransactionID) public
      returns (string memory)
      {
          rollbackXATransaction(_xaTransactionID);
          return deleteXATransactionTask(_xaTransactionID);
      }
  
      function getLatestXATransactionID() public view
      returns (string memory)
      {
          if(head == tail) {
              return NULL_FLAG;
          } else {
              return xaTransactionIDs[uint256(head)];
          }
      }
  
      function getXATransactionState(string memory _path) public view
      returns (string memory)
      {
          address addr = getAddressByPath(_path);
          if(!lockedContracts[addr].locked) {
              return NULL_FLAG;
          } else {
              string memory xaTransactionID = lockedContracts[addr].xaTransactionID;
              uint256 index = xaTransactions[xaTransactionID].stepNum;
              uint256 seq = index == 0 ? 0 : xaTransactions[xaTransactionID].seqs[index-1];
              return string(abi.encodePacked(xaTransactionID, " ", uint256ToString(seq)));
          }
      }
  
      function addXATransaction(string memory _xaTransactionID) internal
      {
          tail++;
          xaTransactionIDs.push(_xaTransactionID);
      }
  
      function deleteXATransactionTask(string memory _xaTransactionID) internal
      returns (string memory)
      {
          if(head == tail) {
              revert("delete nonexistent xa transaction");
          }
  
          if(!sameString(xaTransactionIDs[head], _xaTransactionID)) {
              revert("delete unmatched xa transaction");
          }
  
          head++;
          return SUCCESS_FLAG;
      }
  
      // internal call
      function callContract(address _contractAddress, string memory _sig, bytes memory _args) internal
      returns(bytes memory result)
      {
          bytes memory sig = abi.encodeWithSignature(_sig);
          bool success;
          (success, result) = address(_contractAddress).call(abi.encodePacked(sig, _args));
          if(!success) {
              revert(string(result));
          }
      }
  
      // internal call
      function callContract(address _contractAddress, bytes memory _argsWithMethodId) internal
      returns(bytes memory result)
      {
          bool success;
          (success, result) = address(_contractAddress).call(_argsWithMethodId);
          if(!success) {
              //(string memory error) = abi.decode(result, (string));
              revert(string(result));
          }
      }
  
  
      // retrive address from CNS
      function getAddressByName(string memory _name, bool revertNotExist) internal view
      returns (address)
      {
          string memory strJson = cns.selectByName(_name);
  
          bytes memory str = bytes(strJson);
          uint256 len = str.length;
  
          uint256 index = newKMP(str, bytes("\"sserdda\""));
          if(index == 0) {
              if(revertNotExist) {
                  revert("the name's address not exist.");
              }
              return address(0x0);
          }
  
          bytes memory addr = new bytes(ADDRESS_LEN);
          uint256 start = 0;
          for(uint256 i = index; i < len; i++) {
              if(str[i] == byte('0') && str[i+1] == byte('x')) {
                  start = i;
                  break;
              }
          }
  
          for(uint256 i = 0; i < ADDRESS_LEN; i++) {
              addr[i] = str[start + i];
          }
  
          return bytesToAddress(addr);
      }
  
      // retrive address from CNS
      function getAddressByPath(string memory _path) internal view
      returns (address)
      {
          string memory name = getNameByPath(_path);
          return getAddressByName(name, true);
      }
  
      // input must be a valid path like "zone.chain.resource"
      function getNameByPath(string memory _path) internal pure
      returns (string memory)
      {
          bytes memory path = bytes(_path);
          uint256 len = path.length;
          uint256 nameLen = 0;
          uint256 index = 0;
          for(uint256 i = len - 1; i > 0; i--) {
              if(path[i] == SEPARATOR) {
                  index = i + 1;
                  break;
              } else {
                  nameLen++;
              }
          }
  
          bytes memory name = new bytes(nameLen);
          for(uint256 i = 0; i < nameLen; i++) {
              name[i] = path[index++];
          }
  
          return string(name);
      }
  
      /*
          ["a.b.c1", "a.b.c2"]
      */
      function pathsToJson(string memory _transactionID) internal view
      returns(string memory)
      {
          uint256 len = xaTransactions[_transactionID].paths.length;
          string memory paths = string(abi.encodePacked('["', xaTransactions[_transactionID].paths[0], '"'));
          for(uint256 i = 1; i < len; i++) {
              paths = string(abi.encodePacked(paths, ',"', xaTransactions[_transactionID].paths[i], '"'));
          }
          return string(abi.encodePacked(paths, ']'));
      }
  
      /*
      [
          {
      	    "accountIdentity":"0x12",
              "xaTransactionSeq": 233,
      		"path": "a.b.c1",
      		"timestamp": 233,
      		"method": "set",
      		"args": "0010101"
      	},
          {
      	    "accountIdentity":"0x12",
              "xaTransactionSeq": 233,
      		"path": "a.b.c1",
      		"timestamp": 233,
      		"method": "set",
      		"args": "0010101"
      	}
      ]
      */
      function xaTransactionStepArrayToJson(string memory _transactionID, uint256[] memory _seqs, uint256 _len) internal view
      returns(string memory result)
      {
          if(_len == 0) {
              return '[]';
          }
  
          result = string(abi.encodePacked('[', xatransactionStepToJson(xaTransactionSteps[getXATransactionStepKey(_transactionID, _seqs[0])], _seqs[0])));
          for(uint256 i = 1; i < _len; i++) {
              result = string(abi.encodePacked(result, ',', xatransactionStepToJson(xaTransactionSteps[getXATransactionStepKey(_transactionID, _seqs[i])], _seqs[i])));
          }
  
          return string(abi.encodePacked(result, ']'));
      }
  
      /*
      {
          "xaTransactionSeq": 233,
          "accountIdentity":"0x12",
  		"path": "a.b.c1",
  		"timestamp": 233,
  		"method": "set",
  		"args": "0010101"
  	}
      */
      function xatransactionStepToJson(XATransactionStep memory _xaTransactionStep, uint256 _XATransactionSeq) internal pure
      returns(string memory)
      {
          return string(abi.encodePacked('{"xaTransactionSeq":', uint256ToString(_XATransactionSeq), ',',
              '"accountIdentity":"', _xaTransactionStep.accountIdentity, '",',
              '"path":"', _xaTransactionStep.path, '",',
              '"timestamp":', uint256ToString(_xaTransactionStep.timestamp), ',',
              '"method":"', getMethodFromFunc(_xaTransactionStep.func), '",',
              '"args":"', bytesToHexString(_xaTransactionStep.args), '"}')
          );
      }
  
      function isExistedXATransaction(string memory _xaTransactionID) internal view
      returns (bool)
      {
          return xaTransactions[_xaTransactionID].startTimestamp != 0;
      }
  
      function isValidXATransactionSep(string memory _xaTransactionID, uint256 _XATransactionSeq) internal view
      returns(bool)
      {
          uint256 index = xaTransactions[_xaTransactionID].stepNum;
          return (index == 0) || (_XATransactionSeq > xaTransactions[_xaTransactionID].seqs[index-1]);
      }
  
      function deleteLockedContracts(string memory _xaTransactionID) internal
      {
          uint256 len = xaTransactions[_xaTransactionID].contractAddresses.length;
          for(uint256 i = 0; i < len; i++) {
              address contractAddress = xaTransactions[_xaTransactionID].contractAddresses[i];
              delete lockedContracts[contractAddress];
          }
      }
  
      /* a famous algorithm for finding substring
         match starts with tail, and the target must be "\"sserdda\""
      */
      function newKMP(bytes memory _str, bytes memory _target) internal pure
      returns (uint256)
      {
          int256 strLen = int256(_str.length);
          int256 tarLen = int256(_target.length);
  
          // next array for target "\"sserdda\""
          int8[9] memory nextArray = [-1,0,0,0,0,0,0,0,0];
  
          int256 i = strLen;
          int256 j = 0;
  
          while (i > 0 && j < tarLen) {
              if (j == -1 || _str[uint256(i-1)] == _target[uint256(j)]) {
                  i--;
                  j++;
              } else {
                  j = int256(nextArray[uint256(j)]);
              }
          }
  
          if ( j == tarLen) {
              return uint256(i + tarLen);
          }
  
          return 0;
      }
  
      // func(string,uint256) => func_flag(string,uint256)
      function getRevertFunc(string memory _func, string memory _revertFlag) internal pure
      returns(string memory)
      {
          bytes memory funcBytes = bytes(_func);
          bytes memory flagBytes = bytes(_revertFlag);
          uint256 funcLen = funcBytes.length;
          uint256 flagLen = flagBytes.length;
          bytes memory newFunc = new bytes(funcLen + flagLen);
  
          byte c = byte('(');
          uint256 index = 0;
          uint256 point = 0;
  
          for(uint256 i = 0; i < funcLen; i++) {
              if(funcBytes[i] != c) {
                  newFunc[index++] = funcBytes[i];
              } else {
                  point = i;
                  break;
              }
          }
  
          for(uint256 i = 0; i < flagLen; i++) {
              newFunc[index++] = flagBytes[i];
          }
  
          for(uint256 i = point; i < funcLen; i++) {
              newFunc[index++] = funcBytes[i];
          }
  
          return string(newFunc);
      }
  
      // func(string,uint256) => func
      function getMethodFromFunc(string memory _func) internal pure
      returns(string memory)
      {
          bytes memory funcBytes = bytes(_func);
          uint256 funcLen = funcBytes.length;
          bytes memory temp = new bytes(funcLen);
  
          byte c = byte('(');
          uint256 index = 0;
  
          for(uint256 i = 0; i < funcLen; i++) {
              if(funcBytes[i] != c) {
                  temp[index++] = funcBytes[i];
              } else {
                  break;
              }
          }
  
          bytes memory result = new bytes(index);
          for(uint256 i = 0; i < index; i++) {
              result[i] = temp[i];
          }
  
          return string(result);
      }
  
      function getXATransactionStepKey(string memory _transactionID, uint256 _transactionSeq) internal pure
      returns(string memory)
      {
          return string(abi.encodePacked(_transactionID, uint256ToString(_transactionSeq)));
      }
  
      function sameString(string memory _str1, string memory _str2) internal pure
      returns (bool)
      {
          return keccak256(bytes(_str1)) == keccak256(bytes(_str2));
      }
  
      function hexStringToBytes(string memory _hexStr) internal pure
      returns (bytes memory)
      {
          bytes memory bts = bytes(_hexStr);
          require(bts.length%2 == 0);
          bytes memory result = new bytes(bts.length/2);
          uint len = bts.length/2;
          for (uint i = 0; i < len; ++i) {
              result[i] = byte(fromHexChar(uint8(bts[2*i])) * 16 +
                  fromHexChar(uint8(bts[2*i+1])));
          }
          return result;
      }
  
      function fromHexChar(uint8 _char) internal pure
      returns (uint8)
      {
          if (byte(_char) >= byte('0') && byte(_char) <= byte('9')) {
              return _char - uint8(byte('0'));
          }
          if (byte(_char) >= byte('a') && byte(_char) <= byte('f')) {
              return 10 + _char - uint8(byte('a'));
          }
          if (byte(_char) >= byte('A') && byte(_char) <= byte('F')) {
              return 10 + _char - uint8(byte('A'));
          }
      }
  
      function stringToUint256(string memory _str) public pure
      returns (uint256)
      {
          bytes memory bts = bytes(_str);
          uint256 result = 0;
          uint256 len = bts.length;
          for (uint256 i = 0; i < len; i++) {
              if (uint8(bts[i]) >= 48 && uint8(bts[i]) <= 57) {
                  result = result * 10 + (uint8(bts[i]) - 48);
              }
          }
          return result;
      }
  
      function uint256ToString(uint256 _value) internal pure
      returns (string memory)
      {
          bytes32 result;
          if (_value == 0) {
              return "0";
          } else {
              while (_value > 0) {
                  result = bytes32(uint(result) / (2 ** 8));
                  result |= bytes32(((_value % 10) + 48) * 2 ** (8 * 31));
                  _value /= 10;
              }
          }
          return bytes32ToString(result);
      }
  
      function bytesToHexString(bytes memory _bts) internal pure
      returns (string memory result)
      {
          uint256 len = _bts.length;
          bytes memory s = new bytes(len * 2);
          for (uint256 i = 0; i < len; i++) {
              byte befor = byte(_bts[i]);
              byte high = byte(uint8(befor) / 16);
              byte low = byte(uint8(befor) - 16 * uint8(high));
              s[i*2] = convert(high);
              s[i*2+1] = convert(low);
          }
          result = string(s);
      }
  
      function bytes32ToString(bytes32 _bts32) internal pure
      returns (string memory)
      {
  
          bytes memory result = new bytes(_bts32.length);
  
          uint len = _bts32.length;
          for(uint i = 0; i < len; i++) {
              result[i] = _bts32[i];
          }
  
          return string(result);
      }
  
      function bytesToAddress(bytes memory _address) internal pure
      returns (address)
      {
          if(_address.length != 42) {
              revert(string(abi.encodePacked("cannot covert ", _address, "to bcos address")));
          }
  
          uint160 result = 0;
          uint160 b1;
          uint160 b2;
          for (uint i = 2; i < 2 + 2 * 20; i += 2) {
              result *= 256;
              b1 = uint160(uint8(_address[i]));
              b2 = uint160(uint8(_address[i + 1]));
              if ((b1 >= 97) && (b1 <= 102)) {
                  b1 -= 87;
              } else if ((b1 >= 65) && (b1 <= 70)) {
                  b1 -= 55;
              } else if ((b1 >= 48) && (b1 <= 57)) {
                  b1 -= 48;
              }
  
              if ((b2 >= 97) && (b2 <= 102)) {
                  b2 -= 87;
              } else if ((b2 >= 65) && (b2 <= 70)) {
                  b2 -= 55;
              } else if ((b2 >= 48) && (b2 <= 57)) {
                  b2 -= 48;
              }
              result += (b1 * 16 + b2);
          }
          return address(result);
      }
  
      function addressToString(address _addr) internal pure
      returns (string memory)
      {
          bytes memory result = new bytes(40);
          for (uint i = 0; i < 20; i++) {
              byte temp = byte(uint8(uint(_addr) / (2 ** (8 * (19 - i)))));
              byte b1 = byte(uint8(temp) / 16);
              byte b2 = byte(uint8(temp) - 16 * uint8(b1));
              result[2 * i] = convert(b1);
              result[2 * i + 1] = convert(b2);
          }
          return string(abi.encodePacked("0x", string(result)));
      }
  
      function convert(byte _b) internal pure
      returns (byte)
      {
          if (uint8(_b) < 10) {
              return byte(uint8(_b) + 0x30);
          } else {
              return byte(uint8(_b) + 0x57);
          }
      }
  }
  
  contract CNSPrecompiled {
      function insert(string memory name, string memory version, string memory addr, string memory abiStr) public returns(int256);
      function selectByName(string memory name) public view returns (string memory);
      function selectByNameAndVersion(string memory name, string memory version) public view returns (string memory);
  }
  ```

- golang 版本

  ```go
  /*
  *   v1.0.0
  *   proxy contract for WeCross
  *   main entrance of all contract call
   */
  
  package main
  
  import (
  	"bytes"
  	"encoding/json"
  	"fmt"
  	"github.com/hyperledger/fabric/core/chaincode/shim"
  	"github.com/hyperledger/fabric/protos/peer"
  	"strconv"
  	"strings"
  )
  
  const (
  	Version            = "v1.0.0"
  	RevertFlag         = "_revert"
  	Separator          = "."
  	NullFlag           = "null"
  	SuccessFlag        = "success"
  	XAStatusProcessing = "processing"
  	XAStatusCommitted  = "committed"
  	XAStatusRolledback = "rolledback"
  
  	XATransactionListLenKey = "XATransactionLen"
  	XATaskHeadKey           = "XATransactionTaskHead"
  	ChannelKey              = "Channel"
  	LockContractKey         = "Contract-%s"           // %s: chaincode name
  	XATransactionKey        = "XATransaction-%s-info" // %s: xa transaction id
  	XATransactionTaskKey    = "XATransaction-%d-task" // %d: index
  )
  
  type XATransactionStep struct {
  	Seq       uint64 `json:"xaTransactionSeq"`
  	Identity  string `json:"accountIdentity"`
  	Path      string `json:"path"`
  	Timestamp uint64 `json:"timestamp"`
  	Method    string `json:"method"`
  	Args      string `json:"args"`
  }
  
  type XATransaction struct {
  	TransactionID      string              `json:"xaTransactionID"`
  	Identity           string              `json:"accountIdentity"`
  	Contracts          []string            `json:"contracts"`
  	Paths              []string            `json:"paths"` // all paths related to this transaction
  	Status             string              `json:"status"`
  	StartTimestamp     uint64              `json:"startTimestamp"`
  	CommitTimestamp    uint64              `json:"commitTimestamp"`
  	RollbackTimestamp  uint64              `json:"rollbackTimestamp"`
  	Seqs               []uint64            `json:"seqs"`
  	XATransactionSteps []XATransactionStep `json:"xaTransactionSteps"`
  }
  
  type LockedContract struct {
  	//Path           string  `json:"path"`
  	XATransactionID string `json:"xaTransactionID"`
  }
  
  type Proxy struct {
  }
  
  func (p *Proxy) Init(stub shim.ChaincodeStubInterface) (res peer.Response) {
  	defer func() {
  		if r := recover(); r != nil {
  			res = shim.Error(fmt.Sprintf("%v", r))
  		}
  	}()
  	fn, args := stub.GetFunctionAndParameters()
  
  	switch fn {
  	case "init":
  		res = p.init(stub, args)
  	default:
  		res = shim.Success(nil)
  	}
  	return
  }
  
  func (p *Proxy) Invoke(stub shim.ChaincodeStubInterface) (res peer.Response) {
  	defer func() {
  		if r := recover(); r != nil {
  			res = shim.Error(fmt.Sprintf("%v", r))
  		}
  	}()
  
  	fn, args := stub.GetFunctionAndParameters()
  
  	switch fn {
  	case "init":
  		res = p.init(stub, args)
  	case "getVersion":
  		res = p.getVersion()
  	case "constantCall":
  		res = p.constantCall(stub, args)
  	case "sendTransaction":
  		res = p.sendTransaction(stub, args)
  	case "startXATransaction":
  		res = p.startXATransaction(stub, args)
  	case "commitXATransaction":
  		res = p.commitXATransaction(stub, args)
  	case "rollbackXATransaction":
  		res = p.rollbackXATransaction(stub, args)
  	case "getXATransactionNumber":
  		res = p.getXATransactionNumber(stub)
  	case "listXATransactions":
  		res = p.listXATransactions(stub, args)
  	case "getXATransaction":
  		res = p.getXATransaction(stub, args)
  	case "getLatestXATransaction":
  		res = p.getLatestXATransaction(stub)
  	case "rollbackAndDeleteXATransactionTask":
  		res = p.rollbackAndDeleteXATransactionTask(stub, args)
  	case "getXATransactionState":
  		res = p.getXATransactionState(stub, args)
  	default:
  		res = shim.Error("invalid function name")
  	}
  
  	return
  }
  
  // set channel
  func (p *Proxy) init(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments, [channel] expected")
  	}
  
  	channel := args[0]
  	err := stub.PutState(ChannelKey, []byte(channel))
  	checkError(err)
  	err = stub.PutState(XATransactionListLenKey, []byte("0"))
  	checkError(err)
  	err = stub.PutState(XATaskHeadKey, []byte("0"))
  	checkError(err)
  
  	return shim.Success([]byte(SuccessFlag))
  }
  
  func (p *Proxy) getVersion() peer.Response {
  	return shim.Success([]byte(Version))
  }
  
  // query
  func (p *Proxy) constantCall(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 4 {
  		return shim.Error("invalid arguments")
  	}
  	xaTransactionID, path, method, thisArgs := args[0], args[1], args[2], args[3]
  
  	chaincodeName := getNameFromPath(path)
  
  	var lockedContract LockedContract
  	isLocked := getLockedContract(stub, chaincodeName, &lockedContract)
  
  	if xaTransactionID == "0" {
  		if isLocked {
  			return shim.Error("resource is locked by unfinished xa transaction: " + lockedContract.XATransactionID)
  		}
  		return callContract(stub, chaincodeName, method, thisArgs)
  	}
  
  	if !isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction id not found")
  	}
  
  	if lockedContract.XATransactionID != xaTransactionID {
  		return shim.Error(path + "is unregistered in xa transaction " + xaTransactionID)
  	}
  
  	return callContract(stub, chaincodeName, method, thisArgs)
  }
  
  // invoke
  func (p *Proxy) sendTransaction(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 6 {
  		return shim.Error("invalid arguments")
  	}
  	uniqueID, xaTransactionID, xaTransactionSeq, path, method, realArgs := args[0], args[1], stringToUint64(args[2]), args[3], args[4], args[5]
  
  	res, err := stub.GetState(uniqueID)
  	checkError(err)
  	if res != nil {
  		return shim.Success(res)
  	}
  
  	chaincodeName := getNameFromPath(path)
  
  	var lockedContract LockedContract
  	isLocked := getLockedContract(stub, chaincodeName, &lockedContract)
  
  	if xaTransactionID == "0" {
  		if isLocked {
  			return shim.Error(path + " is locked by unfinished xa transaction: " + lockedContract.XATransactionID)
  		}
  		return callContract(stub, chaincodeName, method, realArgs)
  	}
  
  	if !isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction not found")
  	}
  
  	var xaTransaction XATransaction
  	getXATransaction(stub, xaTransactionID, &xaTransaction)
  	if xaTransaction.Status == XAStatusCommitted {
  		return shim.Error("xa transaction has been committed")
  	}
  
  	if xaTransaction.Status == XAStatusRolledback {
  		return shim.Error("xa transaction has been rolledback")
  	}
  
  	if lockedContract.XATransactionID != xaTransactionID {
  		return shim.Error(path + "is unregistered in xa transaction " + xaTransactionID)
  	}
  
  	if !isValidSeq(stub, xaTransactionID, xaTransactionSeq) {
  		return shim.Error("xaTransactionSeq should be greater than before")
  	}
  
  	timeStamp, err := stub.GetTxTimestamp()
  	checkError(err)
  
  	// recode transactionStep
  	var xaTransactionStep = XATransactionStep{
  		Seq:       xaTransactionSeq,
  		Identity:  getIdentity(stub),
  		Path:      path,
  		Timestamp: uint64(timeStamp.Seconds),
  		Method:    method,
  		Args:      realArgs,
  	}
  
  	xaTransaction.Seqs = append(xaTransaction.Seqs, xaTransactionSeq)
  	xaTransaction.XATransactionSteps = append(xaTransaction.XATransactionSteps, xaTransactionStep)
  
  	// recode xaTransaction
  	xa, err := json.Marshal(&xaTransaction)
  	checkError(err)
  	err = stub.PutState(getXATransactionKey(xaTransactionID), xa)
  	checkError(err)
  
  	response := callContract(stub, chaincodeName, method, realArgs)
  	if response.Status == shim.OK {
  		err = stub.PutState(uniqueID, response.Payload)
  		checkError(err)
  	}
  	return response
  }
  
  /*
   * @args transactionID || selfPaths || otherPaths
   * result: success
   */
  func (p *Proxy) startXATransaction(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	argsLen := len(args)
  	if argsLen != 3 {
  		return shim.Error("invalid arguments")
  	}
  
  	xaTransactionID := args[0]
  	if isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction " + xaTransactionID + " already exists")
  	}
  
  	var selfPaths, otherPaths, allPaths, contracts []string
  	err := json.Unmarshal([]byte(args[1]), &selfPaths)
  	checkError(err)
  	err = json.Unmarshal([]byte(args[2]), &otherPaths)
  	checkError(err)
  
  	for i := 0; i < len(selfPaths); i++ {
  		chaincodeName := getNameFromPath(selfPaths[i])
  		contracts = append(contracts, chaincodeName)
  		var lockedContract LockedContract
  		isLocked := getLockedContract(stub, chaincodeName, &lockedContract)
  		// contract conflict
  		if isLocked {
  			return shim.Error(selfPaths[i] + " is locked by unfinished xa transaction: " + lockedContract.XATransactionID)
  		}
  		lockedContract = LockedContract{
  			XATransactionID: xaTransactionID,
  		}
  		lc, err := json.Marshal(&lockedContract)
  		checkError(err)
  		err = stub.PutState(getLockContractKey(chaincodeName), lc)
  		checkError(err)
  		allPaths = append(allPaths, selfPaths[i])
  	}
  
  	for i := 0; i < len(otherPaths); i++ {
  		allPaths = append(allPaths, otherPaths[i])
  	}
  
  	timeStamp, err := stub.GetTxTimestamp()
  	checkError(err)
  	var xaTransaction = XATransaction{
  		TransactionID:      xaTransactionID,
  		Identity:           getIdentity(stub),
  		Contracts:          contracts,
  		Paths:              allPaths,
  		Status:             XAStatusProcessing,
  		StartTimestamp:     uint64(timeStamp.Seconds),
  		CommitTimestamp:    0,
  		RollbackTimestamp:  0,
  		Seqs:               []uint64{},
  		XATransactionSteps: []XATransactionStep{},
  	}
  
  	xa, err := json.Marshal(&xaTransaction)
  	checkError(err)
  	err = stub.PutState(getXATransactionKey(xaTransactionID), xa)
  	checkError(err)
  
  	addXATransaction(stub, xaTransactionID)
  	return shim.Success([]byte(SuccessFlag))
  }
  
  /*
   * result: success
   */
  func (p *Proxy) commitXATransaction(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments")
  	}
  
  	xaTransactionID := args[0]
  	if !isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction not found")
  	}
  
  	var xaTransaction XATransaction
  	getXATransaction(stub, xaTransactionID, &xaTransaction)
  
  	if xaTransaction.Status == XAStatusCommitted {
  		return shim.Error("xa transaction has been committed")
  	}
  
  	if xaTransaction.Status == XAStatusRolledback {
  		return shim.Error("xa transaction has been rolledback")
  	}
  
  	timeStamp, err := stub.GetTxTimestamp()
  	checkError(err)
  	xaTransaction.Status = XAStatusCommitted
  	xaTransaction.CommitTimestamp = uint64(timeStamp.Seconds)
  
  	xa, err := json.Marshal(&xaTransaction)
  	checkError(err)
  	err = stub.PutState(getXATransactionKey(xaTransactionID), xa)
  	checkError(err)
  
  	deleteLockedContracts(stub, xaTransactionID)
  	return shim.Success([]byte(SuccessFlag))
  }
  
  /*
   * result: success | warning message
   */
  func (p *Proxy) rollbackXATransaction(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments")
  	}
  
  	xaTransactionID := args[0]
  	if !isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction not found")
  	}
  
  	var xaTransaction XATransaction
  	getXATransaction(stub, xaTransactionID, &xaTransaction)
  
  	if xaTransaction.Status == XAStatusCommitted {
  		return shim.Error("xa transaction has been committed")
  	}
  
  	if xaTransaction.Status == XAStatusRolledback {
  		return shim.Error("xa transaction has been rolledback")
  	}
  
  	var res = SuccessFlag
  	var message = "warning:"
  	for i := len(xaTransaction.XATransactionSteps) - 1; i >= 0; i-- {
  		transactionStep := xaTransaction.XATransactionSteps[i]
  		newMethod := getRevertFunc(transactionStep.Method)
  		chaincodeName := getNameFromPath(transactionStep.Path)
  
  		// call revert function
  		response := callContract(stub, chaincodeName, newMethod, transactionStep.Args)
  		if response.Status != shim.OK {
  			message = message + " revert \"" + transactionStep.Method + "\" failed."
  			res = message
  		}
  	}
  
  	timeStamp, err := stub.GetTxTimestamp()
  	checkError(err)
  	xaTransaction.Status = XAStatusRolledback
  	xaTransaction.RollbackTimestamp = uint64(timeStamp.Seconds)
  
  	xa, err := json.Marshal(&xaTransaction)
  	checkError(err)
  	err = stub.PutState(getXATransactionKey(xaTransactionID), xa)
  	checkError(err)
  
  	deleteLockedContracts(stub, xaTransactionID)
  
  	return shim.Success([]byte(res))
  }
  
  // return json string
  func (p *Proxy) getXATransaction(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments")
  	}
  
  	xaTransactionID := args[0]
  	if !isExistedXATransaction(stub, xaTransactionID) {
  		return shim.Error("xa transaction not found")
  	}
  
  	xa, err := stub.GetState(getXATransactionKey(xaTransactionID))
  	checkError(err)
  
  	return shim.Success(xa)
  }
  
  func (p *Proxy) getXATransactionNumber(stub shim.ChaincodeStubInterface) peer.Response {
  	num, err := stub.GetState(XATransactionListLenKey)
  	checkError(err)
  
  	return shim.Success(num)
  }
  
  // return all transaction ids
  func (p *Proxy) listXATransactions(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 2 {
  		return shim.Error("invalid arguments")
  	}
  
  	xaLen, err := stub.GetState(XATransactionListLenKey)
  	checkError(err)
  	length := bytesToUint64(xaLen)
  
  	var index uint64
  	if "-1" == args[0] {
  		index = length - 1
  	} else {
  		index = stringToUint64(args[0])
  	}
  
  	size := stringToInt(args[1])
  
  	if length == 0 || length < index {
  		return shim.Success([]byte("{\"total\":0,\"xaTransactions\":[]}"))
  	}
  
  	type XAInfo struct {
  		TransactionID string   `json:"xaTransactionID"`
  		Identity      string   `json:"accountIdentity"`
  		Status        string   `json:"status"`
  		Timestamp     uint64   `json:"timestamp"`
  		Paths         []string `json:"paths"`
  	}
  
  	type XAList struct {
  		Total          uint64   `json:"total"`
  		XATransactions []XAInfo `json:"xaTransactions"`
  	}
  
  	var xaList XAList
  	var i int
  
  	for i = 0; i < size && index >= uint64(i); i++ {
  		tid, err := stub.GetState(getTransactionTaskKey(index - uint64(i)))
  		checkError(err)
  
  		var xaTransaction XATransaction
  		getXATransaction(stub, string(tid), &xaTransaction)
  		var info = XAInfo{
  			TransactionID: string(tid),
  			Identity:      getIdentity(stub),
  			Status:        xaTransaction.Status,
  			Timestamp:     xaTransaction.StartTimestamp,
  			Paths:         xaTransaction.Paths,
  		}
  		xaList.XATransactions = append(xaList.XATransactions, info)
  	}
  
  	xaList.Total = length
  	res, err := json.Marshal(&xaList)
  	checkError(err)
  
  	return shim.Success(res)
  }
  
  // called by router to check transaction status
  func (p *Proxy) getLatestXATransaction(stub shim.ChaincodeStubInterface) peer.Response {
  	xaTransactionID := getLatestTransactionID(stub)
  
  	if xaTransactionID == NullFlag {
  		return shim.Success([]byte(NullFlag))
  	}
  
  	return p.getXATransaction(stub, []string{xaTransactionID})
  }
  
  func (p *Proxy) rollbackAndDeleteXATransactionTask(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments")
  	}
  
  	res := p.rollbackXATransaction(stub, args)
  	if res.Status == shim.ERROR {
  		return res
  	}
  
  	return deleteLatestTransaction(stub, args[1])
  }
  
  func (p *Proxy) getXATransactionState(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("invalid arguments")
  	}
  
  	path := args[0]
  	chaincodeName := getNameFromPath(path)
  
  	var lockedContract LockedContract
  	isLocked := getLockedContract(stub, chaincodeName, &lockedContract)
  
  	if !isLocked {
  		return shim.Success([]byte(NullFlag))
  	} else {
  		seq := getCurrentSeq(stub, lockedContract.XATransactionID)
  		return shim.Success([]byte(lockedContract.XATransactionID + " " + strconv.FormatUint(seq, 10)))
  	}
  }
  
  func callContract(stub shim.ChaincodeStubInterface, contract, method, jsonArgs string) peer.Response {
  	// parse args from json str
  	var args []string
  	err := json.Unmarshal([]byte(jsonArgs), &args)
  	checkError(err)
  
  	var trans [][]byte
  	trans = append(trans, []byte(method))
  	for _, param := range args {
  		trans = append(trans, []byte(param))
  	}
  
  	channel, err := stub.GetState(ChannelKey)
  	checkError(err)
  
  	return stub.InvokeChaincode(contract, trans, string(channel))
  }
  
  func getIdentity(stub shim.ChaincodeStubInterface) string {
  	creator, err := stub.GetCreator()
  	checkError(err)
  
  	certStart := bytes.IndexAny(creator, "-----BEGIN")
  	if certStart == -1 {
  		panic("no certificate found")
  	}
  
  	return string(creator[certStart:])
  }
  
  func addXATransaction(stub shim.ChaincodeStubInterface, transactionID string) {
  	xaLen, err := stub.GetState(XATransactionListLenKey)
  	checkError(err)
  
  	index := bytesToUint64(xaLen)
  	err = stub.PutState(getTransactionTaskKey(index), []byte(transactionID))
  	checkError(err)
  
  	err = stub.PutState(XATransactionListLenKey, uint64ToBytes(index+1))
  	checkError(err)
  }
  
  func getLatestTransactionID(stub shim.ChaincodeStubInterface) string {
  	taskLen, err := stub.GetState(XATransactionListLenKey)
  	checkError(err)
  
  	head, err := stub.GetState(XATaskHeadKey)
  	checkError(err)
  
  	if bytesToUint64(taskLen) == 0 || bytesToUint64(head) >= bytesToUint64(taskLen) {
  		return NullFlag
  	}
  
  	id, err := stub.GetState(getTransactionTaskKey(bytesToUint64(head)))
  	checkError(err)
  
  	return string(id)
  }
  
  func deleteLatestTransaction(stub shim.ChaincodeStubInterface, transactionID string) peer.Response {
  	taskLen, err := stub.GetState(XATransactionListLenKey)
  	checkError(err)
  
  	head, err := stub.GetState(XATaskHeadKey)
  	checkError(err)
  
  	if bytesToUint64(taskLen) == 0 || bytesToUint64(head) >= bytesToUint64(taskLen) {
  		return shim.Error("delete nonexistent xa transaction")
  	}
  
  	id, err := stub.GetState(getTransactionTaskKey(bytesToUint64(head)))
  	checkError(err)
  
  	if string(id) != transactionID {
  		return shim.Error("delete unmatched transaction")
  	}
  
  	err = stub.PutState(XATaskHeadKey, uint64ToBytes(bytesToUint64(head)+1))
  	checkError(err)
  
  	return shim.Success([]byte(SuccessFlag))
  }
  
  func getNameFromPath(path string) string {
  	strs := strings.Split(path, Separator)
  	if len(strs) != 3 {
  		panic(fmt.Errorf("invalid path: " + path))
  	}
  
  	return strs[2]
  }
  
  func getRevertFunc(method string) string {
  	return method + RevertFlag
  }
  
  func isExistedXATransaction(stub shim.ChaincodeStubInterface, xaTransactionID string) bool {
  	id, err := stub.GetState(getXATransactionKey(xaTransactionID))
  	checkError(err)
  
  	return id != nil
  }
  
  func isValidSeq(stub shim.ChaincodeStubInterface, xaTransactionID string, seq uint64) bool {
  	var xaTransaction XATransaction
  	getXATransaction(stub, xaTransactionID, &xaTransaction)
  	index := len(xaTransaction.Seqs)
  	return (index == 0) || (seq > xaTransaction.Seqs[index-1])
  }
  
  func getCurrentSeq(stub shim.ChaincodeStubInterface, xaTransactionID string) uint64 {
  	var xaTransaction XATransaction
  	getXATransaction(stub, xaTransactionID, &xaTransaction)
  	index := len(xaTransaction.Seqs)
  	if index == 0 {
  		return 0
  	} else {
  		return xaTransaction.Seqs[index-1]
  	}
  }
  
  func getXATransaction(stub shim.ChaincodeStubInterface, xaTransactionID string, xa *XATransaction) {
  	data, err := stub.GetState(getXATransactionKey(xaTransactionID))
  	checkError(err)
  
  	err = json.Unmarshal(data, xa)
  	checkError(err)
  }
  
  func getLockedContract(stub shim.ChaincodeStubInterface, contract string, lc *LockedContract) bool {
  	state, err := stub.GetState(getLockContractKey(contract))
  	checkError(err)
  
  	if state == nil {
  		return false
  	} else {
  		err = json.Unmarshal(state, lc)
  		checkError(err)
  		return true
  	}
  }
  
  func deleteLockedContracts(stub shim.ChaincodeStubInterface, transactionID string) {
  	var xaTransaction XATransaction
  	getXATransaction(stub, transactionID, &xaTransaction)
  
  	for _, contract := range xaTransaction.Contracts {
  		err := stub.DelState(getLockContractKey(contract))
  		checkError(err)
  	}
  }
  
  func getLockContractKey(contract string) string {
  	return fmt.Sprintf(LockContractKey, contract)
  }
  
  func getXATransactionKey(transactionID string) string {
  	return fmt.Sprintf(XATransactionKey, transactionID)
  }
  
  func getTransactionTaskKey(index uint64) string {
  	return fmt.Sprintf(XATransactionTaskKey, index)
  }
  
  func stringToUint64(str string) uint64 {
  	i, e := strconv.Atoi(str)
  	if e != nil {
  		return 0
  	}
  	return uint64(i)
  }
  
  func stringToInt(str string) int {
  	i, e := strconv.Atoi(str)
  	if e != nil {
  		return 0
  	}
  	return i
  }
  
  func bytesToUint64(bts []byte) uint64 {
  	u, err := strconv.ParseUint(string(bts), 10, 64)
  	checkError(err)
  
  	return u
  }
  
  func uint64ToString(u uint64) string {
  	return strconv.FormatUint(u, 10)
  }
  
  func uint64ToBytes(u uint64) []byte {
  	return []byte(uint64ToString(u))
  }
  func checkError(err error) {
  	if err != nil {
  		panic(err)
  	}
  }
  
  func main() {
  	err := shim.Start(new(Proxy))
  	if err != nil {
  		fmt.Printf("Error: %s", err)
  	}
  }
  
  ```

#### 2.1.2 桥接合约

功能点：

- 注册跨链调用请求

- 查询跨链调用回调结果

- Solidity版 

  ```solidity
  /*
  *   v1.0.0
  *   hub contract for WeCross
  *   main entrance of interchain call
  */
  
  pragma solidity >=0.4.22 <0.6.0;
  pragma experimental ABIEncoderV2;
  
  contract WeCrossHub {
  
      // string constant EVENT_TYPE = "INTERCHAIN";
  
      string constant NULL_FLAG  = "null";
  
      string constant VERSION    = "v1.0.0";
  
      string constant CALL_TYPE_QUERY  = "0";
  
      string constant CALL_TYPE_INVOKE = "1";
  
      string constant CALL_TYPE_GET_BLOCK = "2";
  
      uint256 increment = 0;
  
      uint256 currentIndex = 0;
  
      mapping(uint256 => string) requests;
  
      mapping(string => string[]) callbackResults;
  
      function getVersion() public pure
      returns(string memory)
      {
          return VERSION;
      }
  
      // get current uid
      function getIncrement() public view
      returns(uint256)
      {
          return increment;
      }
  
      // invoke other chain
      function interchainInvoke(string memory _path, string memory _method, string[] memory _args, string memory _callbackPath, string memory _callbackMethod) public
      returns(string memory uid)
      {
          return handleRequest(CALL_TYPE_INVOKE, _path, _method, _args, _callbackPath, _callbackMethod);
      }
  
      // query other chain, not support right now
      function interchainQuery(string memory _path, string memory _method, string[] memory _args, string memory _callbackPath, string memory _callbackMethod) public
      returns(string memory uid)
      {
          return handleRequest(CALL_TYPE_QUERY, _path, _method, _args, _callbackPath, _callbackMethod);
      }
  
      function interchainGetBlock(string memory _path, string memory _method, string[] memory _args, string memory _callbackPath, string memory _callbackMethod) public
      returns(string memory uid)
      {
          return handleRequest(CALL_TYPE_GET_BLOCK, _path, _method, _args, _callbackPath, _callbackMethod);
      }
  
      function handleRequest(string memory _callType, string memory _path, string memory _method, string[] memory _args, string memory _callbackPath, string memory _callbackMethod) private
      returns(string memory uid)
      {
          uid = uint256ToString(++increment);
  
          string[] memory reuqest = new string[](8);
          reuqest[0] = uid;
          reuqest[1] = _callType;
          reuqest[2] = _path;
          reuqest[3] = _method;
          reuqest[4] = serializeStringArray(_args);
          reuqest[5] = _callbackPath;
          reuqest[6] = _callbackMethod;
          reuqest[7] = addressToString(tx.origin);
  
          requests[increment] = serializeStringArray(reuqest);
      }
  
      function getInterchainRequests(uint256 _num) public view
      returns(string memory)
      {
          if(currentIndex == increment) {
              return NULL_FLAG;
          }
  
          uint256 num = _num < (increment - currentIndex) ? _num : (increment - currentIndex);
  
          string[] memory tempRequests = new string[](num);
          for(uint256 i = 0; i < num; i++){
              tempRequests[i] = requests[currentIndex+i+1];
          }
  
          return serializeStringArray(tempRequests);
      }
  
      function updateCurrentRequestIndex(uint256 _index) public
      {
          if(currentIndex < _index) {
              currentIndex = _index;
          }
      }
  
      // _result is json form of arrays
      function registerCallbackResult(string memory _uid, string memory _tid, string memory _seq, string memory _errorCode, string memory _errorMsg, string[] memory _result) public
      {
          string[5] memory result = [_tid, _seq, _errorCode, _errorMsg, serializeStringArray(_result)];
          callbackResults[_uid] = result;
      }
  
      function selectCallbackResult(string memory _uid) public view
      returns(string[] memory)
      {
          return callbackResults[_uid];
      }
  
      function serializeStringArray(string[] memory _arr) internal pure
      returns(string memory jsonStr)
      {
          uint len = _arr.length;
          if(len == 0) {
              return "[]";
          }
  
          jsonStr = '[';
          for (uint i = 0; i < len - 1; i++) {
              jsonStr = string(abi.encodePacked(jsonStr, '"'));
              jsonStr = string(abi.encodePacked(jsonStr, jsonEscape(_arr[i])));
              jsonStr = string(abi.encodePacked(jsonStr, '",'));
          }
  
          jsonStr = string(abi.encodePacked(jsonStr, '"'));
          jsonStr = string(abi.encodePacked(jsonStr, jsonEscape(_arr[len - 1])));
          jsonStr = string(abi.encodePacked(jsonStr, '"'));
          jsonStr = string(abi.encodePacked(jsonStr, ']'));
      }
  
      function jsonEscape(string memory _str) internal pure
      returns(string memory)
      {
          bytes memory bts = bytes(_str);
          uint256 len = bts.length;
          bytes memory temp = new bytes(len * 2);
          uint256 i = 0;
          uint256 j = 0;
          for(; j<len; j++) {
              if(bts[j] == '\\' || bts[j] == '"') {
                  temp[i++] = '\\';
              }
              temp[i++] = bts[j];
          }
  
          bytes memory res = new bytes(i);
          for(j = 0; j < i; j++)
          {
              res[j] = temp[j];
          }
          return string(res);
      }
  
      function uint256ToString(uint256 _value) internal pure
      returns (string memory)
      {
          bytes32 result;
          if (_value == 0) {
              return "0";
          }
  
          while (_value > 0) {
              result = bytes32(uint(result) / (2 ** 8));
              result |= bytes32(((_value % 10) + 48) * 2 ** (8 * 31));
              _value /= 10;
          }
          return bytes32ToString(result);
      }
  
      function bytes32ToString(bytes32 _bts32) internal pure
      returns (string memory)
      {
  
          bytes memory result = new bytes(_bts32.length);
  
          uint len = _bts32.length;
          for(uint i = 0; i < len; i++) {
              result[i] = _bts32[i];
          }
  
          return string(result);
      }
  
      function addressToString(address _addr) internal pure
      returns (string memory)
      {
          bytes memory result = new bytes(40);
          for (uint i = 0; i < 20; i++) {
              byte temp = byte(uint8(uint(_addr) / (2 ** (8 * (19 - i)))));
              byte b1 = byte(uint8(temp) / 16);
              byte b2 = byte(uint8(temp) - 16 * uint8(b1));
              result[2 * i] = convert(b1);
              result[2 * i + 1] = convert(b2);
          }
          return string(abi.encodePacked("0x", string(result)));
      }
  
      function convert(byte _b) internal pure
      returns (byte)
      {
          if (uint8(_b) < 10) {
              return byte(uint8(_b) + 0x30);
          } else {
              return byte(uint8(_b) + 0x57);
          }
      }
  }
  ```

- Golang版

  ```go
  /*
  *   v1.0.0
  *   hub contract for WeCross
  *   main entrance of interchain call
   */
  
  package main
  
  import (
  	"bytes"
  	"encoding/json"
  	"fmt"
  	"github.com/hyperledger/fabric/core/chaincode/shim"
  	"github.com/hyperledger/fabric/protos/peer"
  	"strconv"
  )
  
  const (
  	Version        = "v1.0.0"
  	NilFlag        = "null"
  	CallTypeQuery  = "0"
  	CallTypeInvoke = "1"
  	CallTypeGetBlock = "2"
  
  	ChannelKey         = "channel"
  	CurrentIndexKey    = "current_index"
  	IncrementKey       = "increment"
  	RequestsKey        = "request_%s"          // %s: uid
  	CallbackResultsKey = "callback_results_%s" // %s: uid
  )
  
  type Hub struct {
  }
  
  func (h *Hub) Init(stub shim.ChaincodeStubInterface) (res peer.Response) {
  	defer func() {
  		if r := recover(); r != nil {
  			res = shim.Error(fmt.Sprintf("%v", r))
  		}
  	}()
  
  	_, args := stub.GetFunctionAndParameters()
  	if len(args) != 1 {
  		return shim.Error("invalid arguments, [channel] expected")
  	}
  
  	err := stub.PutState(ChannelKey, []byte(args[0]))
  	checkError(err)
  
  	err = stub.PutState(IncrementKey, []byte("0"))
  	checkError(err)
  
  	err = stub.PutState(CurrentIndexKey, []byte("0"))
  	checkError(err)
  
  	return shim.Success(nil)
  }
  
  func (h *Hub) Invoke(stub shim.ChaincodeStubInterface) (res peer.Response) {
  	defer func() {
  		if r := recover(); r != nil {
  			res = shim.Error(fmt.Sprintf("%v", r))
  		}
  	}()
  
  	fcn, args := stub.GetFunctionAndParameters()
  
  	switch fcn {
  	case "getVersion":
  		res = h.getVersion()
  	case "getIncrement":
  		res = h.getIncrement(stub)
  	case "getInterchainRequests":
  		res = h.getInterchainRequests(stub, args)
  	case "updateCurrentRequestIndex":
  		res = h.updateCurrentRequestIndex(stub, args)
  	case "interchainInvoke":
  		res = h.interchainInvoke(stub, args)
  	case "interchainQuery":
  		res = h.interchainQuery(stub, args)
  	case "interchainGetBlock":
          res = h.interchainGetBlock(stub, args)
  	case "registerCallbackResult":
  		res = h.registerCallbackResult(stub, args)
  	case "selectCallbackResult":
  		res = h.selectCallbackResult(stub, args)
  	default:
  		res = shim.Error("invalid function name")
  	}
  
  	return
  }
  
  func (h *Hub) getVersion() peer.Response {
  	return shim.Success([]byte(Version))
  }
  
  func (h *Hub) getIncrement(stub shim.ChaincodeStubInterface) peer.Response {
  	increment, err := stub.GetState(IncrementKey)
  	checkError(err)
  
  	return shim.Success(increment)
  }
  
  /*
   * invoke other chain
   * @args path || method || args || callbackPath || callbackMethod
   */
  func (h *Hub) interchainInvoke(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 5 {
  		return shim.Error("incorrect number of arguments, expecting 5")
  	}
  
  	uid := handleRequest(stub, CallTypeInvoke, args[0], args[1], args[2], args[3], args[4])
  
  	return shim.Success(uint64ToBytes(uid))
  }
  
  // query other chain, not support right now
  func (h *Hub) interchainQuery(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 5 {
  		return shim.Error("incorrect number of arguments, expecting 5")
  	}
  
  	uid := handleRequest(stub, CallTypeQuery, args[0], args[1], args[2], args[3], args[4])
  
  	return shim.Success(uint64ToBytes(uid))
  }
  
  func (h *Hub) interchainGetBlock(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 5 {
  		return shim.Error("incorrect number of arguments, expecting 5")
  	}
  
  	uid := handleRequest(stub, CallTypeGetBlock, args[0], args[1], args[2], args[3], args[4])
  
  	return shim.Success(uint64ToBytes(uid))
  }
  
  func handleRequest(stub shim.ChaincodeStubInterface, callType, path, method, args, callbackPath, callbackMethod string) uint64 {
  	increment, err := stub.GetState(IncrementKey)
  	checkError(err)
  
  	uid := bytesToUint64(increment) + 1
  	creator, err := stub.GetCreator()
  	checkError(err)
  
  	certStart := bytes.IndexAny(creator, "-----BEGIN")
  	if certStart == -1 {
  		panic("no certificate found")
  	}
  
  	request := []string{string(uint64ToBytes(uid)), callType, path, method, args, callbackPath, callbackMethod, string(creator[certStart:])}
  
  	requestData, err := json.Marshal(request)
  	checkError(err)
  
  	err = stub.PutState(IncrementKey, uint64ToBytes(uid))
  	checkError(err)
  
  	err = stub.PutState(getRequestsKey(string(uint64ToBytes(uid))), requestData)
  	checkError(err)
  
  	return uid
  }
  
  /*
   * @args uid || tid || seq || errorCOde || errorMsg || result
   * result is json form of arrays
   */
  func (h *Hub) registerCallbackResult(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 6 {
  		return shim.Error("incorrect number of arguments, expecting 6")
  	}
  
  	result := []string{args[1], args[2], args[3], args[4], args[5]}
  
  	resultData, err := json.Marshal(result)
  	checkError(err)
  
  	err = stub.PutState(getCallbackResultsKey(args[0]), resultData)
  	checkError(err)
  
  	return shim.Success(nil)
  }
  
  func (h *Hub) selectCallbackResult(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("incorrect number of arguments, expecting 1")
  	}
  
  	uid := args[0]
  	result, err := stub.GetState(getCallbackResultsKey(uid))
  	checkError(err)
  
  	return shim.Success(result)
  }
  
  func (h *Hub) getInterchainRequests(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("incorrect number of arguments, [num] expected")
  	}
  
  	increment, err := stub.GetState(IncrementKey)
  	checkError(err)
  
  	currentIndex, err := stub.GetState(CurrentIndexKey)
  	checkError(err)
  
  	total := bytesToUint64(increment)
  	current := bytesToUint64(currentIndex)
  	if total == current {
  		return shim.Success([]byte(NilFlag))
  	}
  
  	num := bytesToUint64([]byte(args[0]))
  	var realNum uint64
  
  	if num < (total - current) {
  		realNum = num
  	} else {
  		realNum = total - current
  	}
  
  	var tempRequests []string
  	var i uint64
  	for i = 0; i < realNum; i++ {
  		request, err := stub.GetState(getRequestsKey(string(uint64ToBytes(current + i + 1))))
  		checkError(err)
  		tempRequests = append(tempRequests, string(request))
  	}
  
  	requestsData, err := json.Marshal(tempRequests)
  	checkError(err)
  
  	return shim.Success(requestsData)
  }
  
  func (h *Hub) updateCurrentRequestIndex(stub shim.ChaincodeStubInterface, args []string) peer.Response {
  	if len(args) != 1 {
  		return shim.Error("incorrect number of arguments, [uid] expected")
  	}
  
  	increment, err := stub.GetState(IncrementKey)
  	checkError(err)
  
  	currentIndex, err := stub.GetState(CurrentIndexKey)
  	checkError(err)
  
  	total := bytesToUint64(increment)
  	current := bytesToUint64(currentIndex)
  	index := bytesToUint64([]byte(args[0]))
  
  	if current < index && index <= total {
  		err = stub.PutState(CurrentIndexKey, []byte(args[0]))
  		checkError(err)
  	}
  	return shim.Success(nil)
  }
  
  func getRequestsKey(uid string) string {
  	return fmt.Sprintf(RequestsKey, uid)
  }
  
  func getCallbackResultsKey(uid string) string {
  	return fmt.Sprintf(CallbackResultsKey, uid)
  }
  
  func bytesToUint64(bts []byte) uint64 {
  	u, err := strconv.ParseUint(string(bts), 10, 64)
  	checkError(err)
  
  	return u
  }
  
  func uint64ToBytes(u uint64) []byte {
  	return []byte(strconv.FormatUint(u, 10))
  }
  
  func checkError(err error) {
  	if err != nil {
  		panic(err)
  	}
  }
  
  func main() {
  	err := shim.Start(new(Hub))
  	if err != nil {
  		fmt.Printf("Error: %s", err)
  	}
  }
  
  ```

### 2.2  java 插件

#### 2.2.1 java 核心组件

#### Java核心组件

- StubFactory
  - 组件实例化工厂类
- Account
  - 区块链账户，用于交易签名
- Connection
  - 调用区块链SDK接口，与区块链交互
- Driver
  - 交易、交易回执、区块等与区块链相关数据的编解码
  - 实现`Stub`的基础接口
  - 调用`Connection`对象的发送入口与区块链交互

##### StubFactory

- 功能描述:
  - 添加`@Stub`注解，定义插件类型
  - 提供`Account`、`Connection`、`Driver`实例化入口
  - @Stub定义了插件类型，添加@Stub注解的插件才能被Wecross Router识别加载

- 接口定义

```
public interface StubFactory {
    public Driver newDriver();
    public Connection newConnection(String path);
    public Account newAccount(Map<String, Object> properties);
}
```

- 接口列表
  - newDriver
    - 实例化`Driver`对象
  - newConnection
    - 实例化`Connection`对象
      - `path`:配置文件路径，配置文件名称默认`stub.toml`
  - newAccount
    - 实例化`Account`对象
      - `properties`:`Account`对象实例化的参数
- `FISCO-BCOS StubFactory`示例

```
/** @Stub注解，插件类型: BCOS2.0 */
@Stub("BCOS2.0") 
public class BCOSStubFactory implements StubFactory {
    @Override
    public Driver newDriver() {
        Driver driver = new BCOSDriver();
        /** 其他逻辑 */
        return driver;
    }
    @Override
    public Connection newConnection(String path) {
        Connection connection = new BCOSConnection();
        /** 解析配置文件，初始化 BCOSConnection */
        return connection;
    }
    @Override
    public Account newAccount(Map<String, Object> properties) {
        Account account = new BCOSAccount();
        /** 根据properties参数，初始化 BCOSAccount */
        return account;
    }
}
```

#### 2.2.2 Account

- 功能描述
  - 交易签名
  - 链账户签名、验签
- 接口定义

```
public interface Account {
    String getName();
    String getType();
    String getIdentity();
    int getKeyID();
    boolean isDefault();
}
```

- 接口列表
  - getName
    - 链账户名称，自定义
  - getType
    - 链账户类型，与Stub类型保持一致
  - getIdentify
    - 链账户标记符，通常为链账户公钥
  - isDefault
    - 当前账户是否为默认链账户
  - getKeyID
    - 链账户KeyID

#### 2.2.3 Connection

- 功能描述
  - 解析配置文件，初始化区块链`JavaSDK`，参考下面`配置文件`小节
  - 为`Driver`提供统一的发送接口，与区块链进行交互
  - 获取链上的资源列表
- 接口定义

```
public interface Connection {
    Response send(Request request);
    List<ResourceInfo> getResources();
}
```

接口列表

- `getResources`

  - 获取区块链上的资源列表

    ```
    /** 资源对象 */
    public class ResourceInfo {
        /** 资源名称 */
        private String name;
        /** 资源类型，用户自定义 */
        private String stubType;
        /** 资源属性 */
        private Map<Object, Object> properties = new HashMap<Object, Object>();
    }
    ```

- `send`

  - 发送接口

    - `Request request`: 请求对象，包括请求类型、请求内容

      ```
      public class Request {
          // 请求类型，自定义类型
          private int type;
          // 请求内容，序列化的请求参数
          private byte[] data;
      }
      ```

    - `Response response`: 返回对象，包括返回状态、描述信息、返回内容

      ```
      public class Response {
          // 返回状态码
          private int errorCode;
          // 描述信息
          private String errorMessage;
          // 返回内容，序列化的返回参数
          private byte[] data;
      }
      ```

`FISCO-BCOS BCOSConnection`示例:

```
// Request type定义，自定义
public class BCOSRequestType {
    // 查询操作
    public static final int CALL = 1000;
    // 发送交易
    public static final int SEND_TRANSACTION = 1001;
    // 获取块高
    public static final int GET_BLOCK_NUMBER = 1002;
    // 获取区块
    public static final int GET_BLOCK_BY_NUMBER = 1003;
    // 获取交易证明
    public static final int GET_TRANSACTION_PROOF = 1004;
}

// Connection定义各个类型消息的处理方式
public class BCOSConnection implements Connection {
    /** 发送入口，区分消息类型，调用区块链RPC接口 */
    @Override
    public Response send(Request request) {
        switch (request.getType()) {
            /** 查询 */
            case BCOSRequestType.CALL:
                /** call请求 */
                break;
            /** 发送交易 */
            case BCOSRequestType.SEND_TRANSACTION:
                /** sendTransaction请求 */
                break;
            /** 获取区块头 */
            case BCOSRequestType.GET_BLOCK_NUMBER:
                /** 获取区块高度请求 */
                break;
            /** 获取块高 */
            case BCOSRequestType.GET_BLOCK_BY_NUMBER:
                /** 获取区块 */
                break;
            /** 获取交易证明 */
            case BCOSRequestType.GET_TRANSACTION_PROOF:
                /** 获取交易证明 */
                break;
        }
    }
}
```

- 配置文件 配置文件主要包括区块链`JavaSDK`初始化需要的参数，也可以包含其他的一些附加信息，由用户自定义。配置默认位于`chains/`目录，可以配置多个stub，每个stub位于单独的子目录，配置文件名称`stub.toml`。

  ```
  # 目录结构, conf/chains/stub名称/
  conf/chains/
          └── bcos # stub名称: bcos
              └── stub.toml # stub.toml配置文件
              # 其他文件列表，比如：证书文件
  ```

`stub.toml`解析流程可以参考[FISCO-BCOS Stub stub.toml解析](https://github.com/WeBankBlockchain/WeCross-BCOS2-Stub/blob/dev/src/main/java/com/webank/wecross/stub/bcos/config/BCOSStubConfigParser.java)

FISCO-BCOS stub.toml示例

```
[common]    # 通用配置
    name = 'bcos' # 名称，必须项
    type = 'BCOS2.0' # 必须项，插件类型，与插件@Stub注解定义的类型保持一致


[chain]     # FISCO-BCOS 属性
    groupId = 1 # default 1
    chainId = 1 # default 1

[channelService]    # FISCO-BCOS JavaSDK配置
    caCert = 'ca.crt'
    sslCert = 'sdk.crt'
    sslKey = 'sdk.key'
    timeout = 300000  # 超时时间
    connectionsStr = ['127.0.0.1:20200', '127.0.0.1:20201', '127.0.0.1:20202'] # 连接列表
```

#### 2.2.4 Driver

- 功能描述
  - 发送交易
  - 状态查询
  - 查询块高
  - 查询区块
  - 获取交易证明
  - 交易、区块编解码
  - 验证交易
  - 查询资源列表
- 接口定义

```
public interface Driver {
    interface Callback {
        void onTransactionResponse(
                TransactionException transactionException, TransactionResponse transactionResponse);
    }

    ImmutablePair<Boolean, TransactionRequest> decodeTransactionRequest(Request request);

    List<ResourceInfo> getResources(Connection connection);

    void asyncCall(
            TransactionContext context,
            TransactionRequest request,
            boolean byProxy,
            Connection connection,
            Driver.Callback callback);

    void asyncSendTransaction(
            TransactionContext context,
            TransactionRequest request,
            boolean byProxy,
            Connection connection,
            Driver.Callback callback);

    interface GetBlockNumberCallback {
        void onResponse(Exception e, long blockNumber);
    }

    void asyncGetBlockNumber(Connection connection, GetBlockNumberCallback callback);

    interface GetBlockCallback {
        void onResponse(Exception e, Block block);
    }

    void asyncGetBlock(
            long blockNumber, boolean onlyHeader, Connection connection, GetBlockCallback callback);

    interface GetTransactionCallback {
        void onResponse(Exception e, Transaction transaction);
    }

    void asyncGetTransaction(
            String transactionHash,
            long blockNumber,
            BlockManager blockManager,
            boolean isVerified,
            Connection connection,
            GetTransactionCallback callback);

    interface CustomCommandCallback {
        void onResponse(Exception error, Object response);
    }

    void asyncCustomCommand(
            String command,
            Path path,
            Object[] args,
            Account account,
            BlockManager blockManager,
            Connection connection,
            CustomCommandCallback callback);

    byte[] accountSign(Account account, byte[] message);

    boolean accountVerify(String identity, byte[] signBytes, byte[] message);
}
```

- 接口列表:
  - asyncCall
  - asyncSendTransaction 状态查询/发送交易
    - TransactionContext context
      - 请求上下文，交易的上下文，包含交易的附属信息
    - TransactionRequest request
    - boolean byProxy
      - 是否通过代理合约查询状态/发送交易
    - Connection connection
      - 发送请求
    - Driver.Callback callback
      - 回调返回
  - asyncGetBlockNumber 获取区块高度
    - Connection connection
      - 发送请求
    - GetBlockNumberCallback callback
      - 回调返回
  - asyncGetBlock 获取区块
    - long blockNumber
      - 区块高度
    - boolean onlyHeader
      - 是否只获取区块头
    - Connection connection
      - 发送请求
    - GetBlockCallback callback
      - 回调返回
  - asyncGetTransaction 获取交易，并且对交易进行合法性验证
    - String transactionHash
      - 交易hash
    - long blockNumber
      - 区块高度
    - BlockManager blockManager
      - 区块管理对象，用于获取区块信息，可以使用区块头部的状态信息校验交易是否合法
    - boolean isVerified
      - 是否校验交易
    - Connection connection
      - 发送请求
    - GetTransactionCallback callback
      - 回调返回
  - asyncCustomCommand 用户自定义其他接口
    - String command
      - 命令
    - Path path
      - 资源
    - Object[] args
      - 参数列表
    - Account account
      - 账户
    - BlockManager blockManager
      - 区块管理对象
    - Connection connection
      - 发送请求
    - CustomCommandCallback callback
      - 回调返回
  - accountSign 链账户`Account`对消息进行签名，返回序列化之后的签名对象
    - Account account
      - 签名账户
    - byte[] message
      - 代签名的消息
  - accountVerify 链账户验签
    - String identity
    - byte[] signBytes
      - 签名对象，`accountSign`的返回值
    - byte[] message
      - 签名的原始消息

## 三、开发模版

跨链服务提供一个`Java`模板工程，加快用户开发 `Stub` 的速度，用户仅需要进行少量的修改。

**获取:**

```
git clone https://github.com/WeBankBlockchain/WeCross-Stub-Dev-Template.git
```

**目录结构:**

```
WeCross-Stub-Dev-Template
├── README.md
├── build.gradle
└── src
    ├── main
    │   ├── java
    │   │   └── wecross
    │   │       └── stub
    │   │           └── demo                  ## Java核心组件，参考上文各个组件的介绍
    │   │               ├── DemoAccount.java      # Account
    │   │               ├── DemoConnection.java   # Connection
    │   │               ├── DemoDriver.java       # Driver
    │   │               └── DemoStubFactory.java  # StubFactory
    │   └── resources
    └── test
        ├── java
        │   └── wecross
        │       └── stub
        │           └── demo
        │               └── DemoStubTest.java
        └── resources
```

**编译:**

```
cd WeCross-Stub-Dev-Template
bash gradlew build

$ tree -L 1 dist/apps
dist/apps
└── WeCross-Stub-Dev-Template-1.0.0-SNAPSHOT.jar
```

## 四、参考链接

- [WeCross-BCOS3-Stub](https://github.com/WeBankBlockchain/WeCross-BCOS3-Stub)

- [WeCross-BCOS2-Stub](https://github.com/WeBankBlockchain/WeCross-BCOS2-Stub)

- [WeCross-Fabric-Stub](https://github.com/WeBankBlockchain/WeCross-Fabric1-Stub)

## 五、注意事项

1. 国密版本的长安链插件和 Fisco Bcos 链插件，由于官方都对 netty 包做过适配改造，因此这两个插件如果部署在统一跨链服务会导致冲突。目前解决的方案是将两个插件部署在两个不同的跨链服务上；

2. 跨链服务如果要同时支持国密版本的长安链和 Fisco Bcos 链插件，需要使用不同的分支管理编译，并且支持国密版本 Fisco Bcos 链插件需要在 `build.grandle` 里增加下面的依赖：

   ```
   dependencies {
       constraints {
           compile group: 'io.netty', name: 'netty-all', version: '4.1.77.Final'
           compile 'io.netty:netty-codec-haproxy:4.1.89.Final'
           compile group: 'org.fisco-bcos', name: 'tcnative', version: '2.0.51.0'
       }
       compile 'org.fisco-bcos:tcnative'
   }
   ```

   